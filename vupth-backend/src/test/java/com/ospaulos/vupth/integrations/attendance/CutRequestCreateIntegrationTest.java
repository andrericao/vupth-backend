package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.attendance.cut.repository.CutRequestRepository;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — BUG5 C2 "solicitar corte", fatia 1 (criar pedido).
 *
 * Regra: <b>POST /attendances/cut-requests</b> (ROLE_CLIENT) so vale com a loja FECHADA agora
 * (gate C1). Cria um {@code CutRequest} PENDING com validade de 5min; loja aberta -> 409.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CutRequestCreateIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired CutRequestRepository cutRequestRepository;

    private final Faker faker = new Faker();

    String clientToken, professionalId, establishmentId, myServiceItemId;
    String otherProfessionalId, otherServiceItemId; // profissional/servico de OUTRA loja

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
        this.establishmentId = estab.id();       // nasce CLOSED (default)
        this.professionalId = owner.id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, owner.token(), establishmentId, "Prof A");
        var sA = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, owner.token(),
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.myServiceItemId = sA.id();

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientToken = client.token();

        var other = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var otherEstab = TestUserUtils.becomeOwner(mvc, mapper, faker, other.token());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, other.token(), otherEstab.id(), "Prof B");
        var sB = TestUserUtils.createServiceItem(mvc, mapper, otherEstab.id(), other.token(),
                ServiceItemType.CORTE_MAQUINA, "Corte B", BigDecimal.valueOf(30));
        this.otherProfessionalId = other.id();
        this.otherServiceItemId = sB.id();

        log.info("[CutRequest] setup — estab={} prof={}", establishmentId, professionalId);
    }

    @AfterAll
    void cleanup() {
        // O Order 10 cria um CutRequest PENDING que persiste; sob reuseForks o Postgres e
        // compartilhado e o join cut_request_service_item barraria o deleteAll de service_item
        // no teardown de outras classes. CASCADE (fk_cut_req_si_cut_request) limpa o join.
        cutRequestRepository.deleteAll();
    }

    private String body(String profId, String estId, String serviceItemId) {
        return "{\"professionalId\":\"" + profId + "\",\"establishmentId\":\"" + estId
                + "\",\"serviceItemIds\":[\"" + serviceItemId + "\"]}";
    }

    private void forceOpen(boolean open) {
        Establishment e = establishmentRepository.findById(UUID.fromString(establishmentId)).orElseThrow();
        e.setOpenStatus(open ? OpenStatus.OPEN : OpenStatus.CLOSED);
        establishmentRepository.save(e);
    }

    @Test
    @Order(10)
    @DisplayName("loja fechada -> 201 cria CutRequest PENDING com expires_at")
    void closedShop_createsPending() throws Exception {
        mvc.perform(post("/attendances/cut-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(body(professionalId, establishmentId, myServiceItemId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.expires_at").exists())
                .andExpect(jsonPath("$.professional_id").value(professionalId));
    }

    @Test
    @Order(20)
    @DisplayName("loja aberta agora -> 409 CUT_REQUEST_NOT_ALLOWED (entra normal)")
    void openShop_conflict() throws Exception {
        forceOpen(true);
        try {
            mvc.perform(post("/attendances/cut-requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", clientToken)
                            .content(body(professionalId, establishmentId, myServiceItemId)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CUT_REQUEST_NOT_ALLOWED"));
        } finally {
            forceOpen(false);
        }
    }

    @Test
    @Order(30)
    @DisplayName("servico de outro profissional -> 422 SERVICE_ITEM_NOT_OF_PROFESSIONAL")
    void serviceItemOfAnotherProfessional_unprocessable() throws Exception {
        mvc.perform(post("/attendances/cut-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(body(professionalId, establishmentId, otherServiceItemId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SERVICE_ITEM_NOT_OF_PROFESSIONAL"));
    }

    @Test
    @Order(40)
    @DisplayName("sem token -> 401")
    void noToken_unauthorized() throws Exception {
        mvc.perform(post("/attendances/cut-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(professionalId, establishmentId, myServiceItemId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(50)
    @DisplayName("profissional inexistente -> 404")
    void unknownProfessional_notFound() throws Exception {
        mvc.perform(post("/attendances/cut-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(body(UUID.randomUUID().toString(), establishmentId, myServiceItemId)))
                .andExpect(status().isNotFound());
    }
}
