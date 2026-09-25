package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — o atendimento criado pelo cliente aparece de forma CONSISTENTE nos
 * read-models (Partner BUG8).
 *
 * <ul>
 *   <li><b>Sintoma (b):</b> {@code GET /attendances?clientId=} passa a devolver os proprios
 *       atendimentos do cliente (antes era owner/profissional-only → cliente tomava 403 e
 *       "nao aparecia nada"). So o proprio JWT; clientId de outro → 403.</li>
 *   <li>Caminho feliz: o mesmo atendimento aparece em {@code professionals-with-queue.waiting_list},
 *       {@code GET /attendances?clientId=self} e {@code GET /attendances/client/me}.</li>
 * </ul>
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QueueVisibilityBug8IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;

    private final Faker faker = new Faker();

    // Profissional 1 (alvo da fila), na loja 1
    String prof1Token, prof1Id, estab1Id;
    UUID serviceItem1;
    // Profissional 2 (outra loja) — dono do proprio item, usado para o caso-orfao (BUG8 c)
    String prof2Token, estab2Id;
    UUID serviceItem2;
    // Cliente
    String clientToken, clientId;
    UUID attendanceId;

    @BeforeAll
    void setup() throws Exception {
        var owner1 = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.prof1Token = owner1.token();
        this.prof1Id = owner1.id();
        var estab1 = TestUserUtils.becomeOwner(mvc, mapper, faker, prof1Token);
        this.estab1Id = estab1.id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, prof1Token, estab1Id, "Prof 1");
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(prof1Id)));
        var s1 = TestUserUtils.createServiceItem(mvc, mapper, estab1Id, prof1Token,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItem1 = UUID.fromString(s1.id());

        // Loja 2 com prof2, que cria o PROPRIO serviceItem2 (nao pertence ao prof1) — BUG8 (c)
        var owner2 = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.prof2Token = owner2.token();
        var estab2 = TestUserUtils.becomeOwner(mvc, mapper, faker, prof2Token);
        this.estab2Id = estab2.id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, prof2Token, estab2Id, "Prof 2");
        // 20/09: profissional nasce OFF e quem nao esta em expediente nao recebe fila.
        // Aqui o expediente e cenario, nao o objeto do teste — entao ele chega pelo atalho.
        TestUserUtils.startShift(professionalRepository, estab1Id);
        TestUserUtils.startShift(professionalRepository, estab2Id);
        var s2 = TestUserUtils.createServiceItem(mvc, mapper, estab2Id, prof2Token,
                ServiceItemType.CORTE_MAQUINA, "Corte 2", BigDecimal.valueOf(30));
        this.serviceItem2 = UUID.fromString(s2.id());

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientToken = client.token();
        this.clientId = client.id();

        Establishment e = establishmentRepository.findById(UUID.fromString(estab1Id)).orElseThrow();
        e.setOpenStatus(OpenStatus.OPEN);
        establishmentRepository.save(e);

        log.info("[BUG8] setup — estab1={} prof1={} client={}", estab1Id, prof1Id, clientId);
    }

    private AttendanceRequest requestFor(UUID professionalId, UUID establishmentId, List<UUID> items) {
        return new AttendanceRequest(
                professionalId, establishmentId, UUID.fromString(clientId), items,
                AttendanceStatus.SCHEDULED, OffsetDateTime.now(), 30, OffsetDateTime.now(), null);
    }

    @Test
    @Order(10)
    @DisplayName("Cliente entra na fila (201) — atendimento criado")
    void client_enqueues() throws Exception {
        MvcResult r = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(
                                requestFor(UUID.fromString(prof1Id), UUID.fromString(estab1Id), List.of(serviceItem1)))))
                .andExpect(status().isCreated())
                .andReturn();
        this.attendanceId = UUID.fromString(new JSONObject(r.getResponse().getContentAsString()).getString("id"));
    }

    @Test
    @Order(20)
    @DisplayName("Aparece no professionals-with-queue.waiting_list do profissional")
    void appears_inProfessionalsWithQueue() throws Exception {
        mvc.perform(get("/establishment/id/{id}/professionals-with-queue", estab1Id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.professionals[0].waiting_list[*].id", hasItem(attendanceId.toString())));
    }

    @Test
    @Order(30)
    @DisplayName("(BUG8 b) GET /attendances?clientId=self devolve o proprio atendimento")
    void appears_inClientIdFilter() throws Exception {
        mvc.perform(get("/attendances").param("clientId", clientId)
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(attendanceId.toString())));
    }

    @Test
    @Order(40)
    @DisplayName("GET /attendances/client/me continua devolvendo o atendimento (consistencia)")
    void appears_inClientMe() throws Exception {
        mvc.perform(get("/attendances/client/me")
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(attendanceId.toString())));
    }

    @Test
    @Order(50)
    @DisplayName("(BUG8 b) clientId de OUTRO usuario → 403 (so lista os proprios)")
    void clientId_ofOther_forbidden() throws Exception {
        mvc.perform(get("/attendances").param("clientId", UUID.randomUUID().toString())
                        .header("Authorization", clientToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(60)
    @DisplayName("(BUG8 c) agendar prof1 com serviceItem de OUTRO prof → 422 SERVICE_ITEM_NOT_OF_PROFESSIONAL")
    void schedule_withServiceItemOfAnotherProfessional_unprocessable() throws Exception {
        mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(
                                requestFor(UUID.fromString(prof1Id), UUID.fromString(estab1Id), List.of(serviceItem2)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SERVICE_ITEM_NOT_OF_PROFESSIONAL"));
    }
}
