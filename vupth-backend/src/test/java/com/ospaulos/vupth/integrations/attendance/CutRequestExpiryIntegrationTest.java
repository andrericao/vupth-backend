package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.attendance.cut.CutRequestStatus;
import com.ospaulos.vupth.domain.attendance.cut.model.CutRequest;
import com.ospaulos.vupth.domain.attendance.cut.repository.CutRequestRepository;
import com.ospaulos.vupth.domain.attendance.cut.service.CutRequestExpiryScheduler;
import com.ospaulos.vupth.domain.attendance.cut.service.CutRequestService;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — BUG5 C2 "solicitar corte", fatia 3 (auto-nega por timer server-side).
 *
 * O ciclo e exercitado DIRETAMENTE ({@code expireDueCutRequests}/{@code reschedule}), sem depender de
 * relogio real — o timer de wall-clock fica desarmado nos testes ({@code vupth.cut-request.auto-arm=false}).
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CutRequestExpiryIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired CutRequestRepository cutRequestRepository;
    @Autowired CutRequestService cutRequestService;
    @Autowired CutRequestExpiryScheduler expiryScheduler;

    private final Faker faker = new Faker();

    String clientToken, professionalId, establishmentId, serviceItemId;

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.professionalId = owner.id();
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
        this.establishmentId = estab.id();       // CLOSED por default -> permite solicitar corte
        TestUserUtils.becomeProfessional(mvc, mapper, faker, owner.token(), establishmentId, "Prof A");
        var s = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, owner.token(),
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemId = s.id();

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientToken = client.token();

        log.info("[CutRequestExpiry] setup — estab={} prof={}", establishmentId, professionalId);
    }

    @AfterEach
    void clean() {
        cutRequestRepository.deleteAll();
    }

    /** Cliente cria um pedido de corte (loja fechada) e devolve o id. */
    private String createRequest() throws Exception {
        String body = "{\"professionalId\":\"" + professionalId + "\",\"establishmentId\":\"" + establishmentId
                + "\",\"serviceItemIds\":[\"" + serviceItemId + "\"]}";
        MvcResult r = mvc.perform(post("/attendances/cut-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return new JSONObject(r.getResponse().getContentAsString()).getString("id");
    }

    private CutRequestStatus statusOf(String id) {
        return cutRequestRepository.findById(UUID.fromString(id)).orElseThrow().getStatus();
    }

    /** Envelhece o pedido no banco (simula o vencimento sem esperar 5min de relogio real). */
    private void age(String id) {
        CutRequest cut = cutRequestRepository.findById(UUID.fromString(id)).orElseThrow();
        cut.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        cutRequestRepository.save(cut);
    }

    @Test
    @Order(10)
    @DisplayName("pedido vencido -> expireDueCutRequests marca EXPIRED e devolve 1")
    void due_expires() throws Exception {
        String id = createRequest();
        age(id);

        int expired = cutRequestService.expireDueCutRequests();

        assertThat(expired).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo(CutRequestStatus.EXPIRED);
        assertThat(cutRequestRepository.findById(UUID.fromString(id)).orElseThrow().getRespondedAt()).isNotNull();
    }

    @Test
    @Order(20)
    @DisplayName("pedido ainda no prazo -> permanece PENDING (nao expira)")
    void notDue_staysPending() throws Exception {
        String id = createRequest(); // expiresAt = agora + 5min (no prazo)

        int expired = cutRequestService.expireDueCutRequests();

        assertThat(expired).isZero();
        assertThat(statusOf(id)).isEqualTo(CutRequestStatus.PENDING);
    }

    @Test
    @Order(30)
    @DisplayName("so o vencido expira; o que esta no prazo segue PENDING")
    void onlyDueExpires() throws Exception {
        String dueId = createRequest();
        String freshId = createRequest();
        age(dueId);

        int expired = cutRequestService.expireDueCutRequests();

        assertThat(expired).isEqualTo(1);
        assertThat(statusOf(dueId)).isEqualTo(CutRequestStatus.EXPIRED);
        assertThat(statusOf(freshId)).isEqualTo(CutRequestStatus.PENDING);
    }

    @Test
    @Order(40)
    @DisplayName("reschedule nao lanca com PENDING ativo e fica ocioso sem PENDING")
    void reschedule_smoke() throws Exception {
        createRequest();
        expiryScheduler.reschedule();          // ha cabeca PENDING -> arma sem erro

        cutRequestRepository.deleteAll();
        expiryScheduler.reschedule();          // sem PENDING -> ocioso, sem erro

        assertThat(cutRequestRepository.count()).isZero(); // chegou aqui = reschedule nao lancou
    }
}
