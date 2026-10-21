package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent;
import com.ospaulos.vupth.domain.client.model.PenaltyType;
import com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository;
import com.ospaulos.vupth.domain.client.repository.ClientRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * relatorios-por-perfil (DEC-94): o cliente recebe um relatorio e o profissional recebe outro. Falha se o campo de
 * outro perfil aparecer: o cliente nunca ve o valor do profissional nem a margem do app; a loja nunca ve a multa nem o
 * lucro do app, e do cliente so o nome (sem e-mail, telefone nem historico de multas).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportsByProfileContractTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;
    @Autowired ClientPenaltyEventRepository penaltyEventRepository;
    @Autowired ClientRepository clientRepository;

    private final Faker faker = new Faker();

    private String ownerToken, establishmentId, clientToken, clientId, attendanceId;

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerToken = owner.token();
        this.establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerToken).id();
        TestUserUtils.openEstablishment(establishmentRepository, establishmentId);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerToken, establishmentId, "Dono Profissional");
        TestUserUtils.startShift(professionalRepository, establishmentId);
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(owner.id())));

        MvcResult created = mvc.perform(post("/establishment/{id}/service-items", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(String.format("""
                                {"establishmentId": "%s", "serviceItemType": "CORTE_MAQUINA", "customName": "Corte",
                                 "price": 32.90, "estimatedMinutes": 30, "pro_receives": 30.00, "base_price": 30.00}
                                """, establishmentId)))
                .andExpect(status().isCreated())
                .andReturn();
        String serviceItemId = new JSONObject(created.getResponse().getContentAsString()).getString("id");

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientToken = client.token();
        this.clientId = client.id();
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(owner.id()), UUID.fromString(establishmentId), UUID.fromString(client.id()),
                List.of(UUID.fromString(serviceItemId)), AttendanceStatus.SCHEDULED,
                OffsetDateTime.now(), 30, OffsetDateTime.now(), null);
        MvcResult result = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        this.attendanceId = new JSONObject(result.getResponse().getContentAsString()).getString("id");

        // uma multa carregada por este atendimento: o cliente a ve (valor e origem); a loja nunca
        ClientPenaltyEvent penalty = new ClientPenaltyEvent(
                clientRepository.findById(UUID.fromString(client.id())).orElseThrow(), PenaltyType.NO_SHOW,
                UUID.fromString(attendanceId), 0, new BigDecimal("5.00"));
        penalty.setChargedAttendanceId(UUID.fromString(attendanceId));
        penaltyEventRepository.save(penalty);
    }

    @AfterAll
    void tearDown() {
        penaltyEventRepository.deleteAll();
    }

    @Test
    @DisplayName("CLIENTE (historico): o corte e a multa, sem o valor do profissional, sem as taxas e sem a margem do app")
    void clientHistory() throws Exception {
        checkClient(get("/attendances/client/me"), "$[0]", "/attendances/client/me");
        checkClient(get("/attendances").queryParam("clientId", clientId), "$[0]", "/attendances?clientId");
        checkClient(get("/attendances").queryParam("clientId", clientId).queryParam("page", "0").queryParam("size", "5"),
                "$.items[0]", "/attendances?clientId&page");
    }

    private void checkClient(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                             String prefix, String where) throws Exception {
        MvcResult r = mvc.perform(request.header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(prefix + ".totalValue").value(32.90))
                .andExpect(jsonPath(prefix + ".penaltyTotal").value(5.00))
                .andExpect(jsonPath(prefix + ".penalties[0].amount").value(5.00))
                .andExpect(jsonPath(prefix + ".proReceives").doesNotExist())
                .andExpect(jsonPath(prefix + ".intermediaryFee").doesNotExist())
                .andExpect(jsonPath(prefix + ".platformFee").doesNotExist())
                .andReturn();
        assertNoneOf(r.getResponse().getContentAsString(), where,
                "proReceives", "pro_receives", "intermediaryFee", "platformFee", "gatewayFee", "gateway_fee",
                "appShare", "app_fee", "app_profit", "appProfit");
    }

    @Test
    @DisplayName("LOJA (profissional e dono): o que recebe e as taxas embutidas, sem multa, sem lucro do app e sem dado pessoal do cliente")
    void storeListing() throws Exception {
        checkStore(get("/attendances").queryParam("establishmentId", establishmentId), "$[0]", "/attendances");
        checkStore(get("/attendances").queryParam("establishmentId", establishmentId)
                .queryParam("page", "0").queryParam("size", "5"), "$.items[0]", "/attendances?page");
    }

    private void checkStore(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                            String prefix, String where) throws Exception {
        MvcResult r = mvc.perform(request.header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(prefix + ".totalValue").value(32.90))
                .andExpect(jsonPath(prefix + ".proReceives").value(30.00))
                .andExpect(jsonPath(prefix + ".intermediaryFee").value(1.99))
                .andExpect(jsonPath(prefix + ".platformFee").value(0.91))
                .andExpect(jsonPath(prefix + ".penalties").doesNotExist())
                .andExpect(jsonPath(prefix + ".penaltyTotal").doesNotExist())
                .andExpect(jsonPath(prefix + ".rated").doesNotExist())
                .andReturn();
        assertNoneOf(r.getResponse().getContentAsString(), where,
                "penalt", "multa", "app_profit", "appProfit", "appShare", "gatewayFee", "gateway_fee",
                "email", "phone", "telefone", "@");
    }

    @Test
    @DisplayName("as multas pendentes do cliente: valor e origem, sem a taxa do gateway nem a parte do app")
    void pendingPenalties() throws Exception {
        penaltyEventRepository.findByClientIdOrderByCreatedAtDesc(UUID.fromString(clientId)).forEach(e -> {
            e.setChargedAttendanceId(null);
            penaltyEventRepository.save(e);
        });
        MvcResult r = mvc.perform(get("/clients/me/pending-penalties").header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5.00))
                .andExpect(jsonPath("$.penalties[0].amount").value(5.00))
                .andReturn();
        assertNoneOf(r.getResponse().getContentAsString(), "pending-penalties", "gatewayFee", "appShare", "gateway_fee");
    }

    private static void assertNoneOf(String body, String where, String... forbidden) {
        String lower = body.toLowerCase();
        for (String word : forbidden) {
            assertThat(lower).as("%s nao pode trazer \"%s\"", where, word).doesNotContain(word.toLowerCase());
        }
    }
}
