package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — a multa e cobrada no PROXIMO atendimento (D4, Andre 17/09 e 25/09).
 *
 * <p>Um mesmo cliente percorre a vida da multa: nasce ao sair depois da tolerancia (pendente, com a
 * origem), vai para o atendimento seguinte na entrada, volta a pendente quando esse atendimento termina
 * sem servico, e fica paga quando um atendimento que a carrega e concluido. O cliente paga o valor
 * cheio; 4% sao do gateway e o resto do app.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PenaltyChargeIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired ClientPenaltyEventRepository penaltyEventRepository;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    String profToken, professionalId, establishmentId, professionalName;
    List<UUID> serviceItemIds;
    String clientId, clientToken;

    String originId, secondId, thirdId;

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profToken = owner.token();
        this.professionalId = owner.id();
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, profToken);
        this.establishmentId = estab.id();
        TestUserUtils.openEstablishment(establishmentRepository, establishmentId);
        this.professionalName = "Prof Multa";
        TestUserUtils.becomeProfessional(mvc, mapper, faker, profToken, establishmentId, professionalName);
        TestUserUtils.startShift(professionalRepository, establishmentId);
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));
        var service = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, profToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(service.id()));

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientId = client.id();
        this.clientToken = client.token();
    }

    // ── helpers ──────────────────────────────────────────────────

    private String enter() throws Exception {
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId), UUID.fromString(establishmentId), UUID.fromString(clientId),
                serviceItemIds, AttendanceStatus.SCHEDULED, OffsetDateTime.now(), 30, OffsetDateTime.now(), null);
        MvcResult r = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return new JSONObject(r.getResponse().getContentAsString()).getString("id");
    }

    private void expireTolerance(String attendanceId) {
        var attendance = attendanceRepository.findById(UUID.fromString(attendanceId)).orElseThrow();
        attendance.setToleranceUntil(OffsetDateTime.now().minusMinutes(1));
        attendanceRepository.save(attendance);
    }

    private void cancel(String attendanceId) throws Exception {
        // D4: o "Sim" do aviso (o maior valor possivel) — a ciencia nao e o objeto deste teste.
        mvc.perform(put("/attendances/{id}/cancel", attendanceId)
                        .param("acknowledgedPenalty", "5.00")
                        .header("Authorization", clientToken))
                .andExpect(status().isOk());
    }

    private JSONObject pending() throws Exception {
        MvcResult r = mvc.perform(get("/clients/me/pending-penalties")
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andReturn();
        return new JSONObject(r.getResponse().getContentAsString());
    }

    /** O atendimento como aparece no historico do cliente (GET /attendances/client/me). */
    private JSONObject history(String attendanceId) throws Exception {
        MvcResult r = mvc.perform(get("/attendances/client/me")
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andReturn();
        JSONArray list = new JSONArray(r.getResponse().getContentAsString());
        for (int i = 0; i < list.length(); i++) {
            if (list.getJSONObject(i).getString("id").equals(attendanceId)) {
                return list.getJSONObject(i);
            }
        }
        throw new AssertionError("atendimento " + attendanceId + " fora do historico");
    }

    private static BigDecimal money(JSONObject json, String field) throws Exception {
        return new BigDecimal(json.get(field).toString());
    }

    // ── casos ────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("Sem multa, nada pendente: total 0.00 e lista vazia")
    void noPenalty_nothingPending() throws Exception {
        JSONObject p = pending();
        assertThat(money(p, "total")).isEqualByComparingTo("0.00");
        assertThat(p.getJSONArray("penalties").length()).isZero();
    }

    @Test
    @Order(20)
    @DisplayName("Saiu depois da tolerancia: a multa fica pendente, com a origem, sem a divisao app/gateway (DEC-94)")
    void exitAfterTolerance_pendingWithOrigin() throws Exception {
        this.originId = enter();
        expireTolerance(originId);
        cancel(originId);

        JSONObject p = pending();
        assertThat(money(p, "total")).isEqualByComparingTo("5.00");
        JSONObject penalty = p.getJSONArray("penalties").getJSONObject(0);
        assertThat(penalty.getString("type")).isEqualTo("LATE_CANCEL");
        assertThat(money(penalty, "amount")).as("o cliente paga o valor cheio").isEqualByComparingTo("5.00");
        // DEC-94: o cliente nunca ve a taxa do gateway nem a parte do app
        assertThat(penalty.has("gatewayFee")).as("sem a taxa do gateway").isFalse();
        assertThat(penalty.has("appShare")).as("sem a parte do app").isFalse();
        assertThat(penalty.getString("originAttendanceId")).isEqualTo(originId);
        assertThat(penalty.getString("originProfessionalName")).as("corte com o profissional tal").isEqualTo(professionalName);
        assertThat(penalty.getString("originEstablishmentName")).isNotBlank();
        assertThat(penalty.getString("originDate")).as("corte do dia tal").isNotBlank();
    }

    @Test
    @Order(30)
    @DisplayName("Na entrada seguinte a multa vai para o novo atendimento: sai do pendente, entra no historico dele")
    void nextIngress_carriesPenalty() throws Exception {
        this.secondId = enter();

        assertThat(money(pending(), "total")).as("em cobranca, nao pendente").isEqualByComparingTo("0.00");

        JSONObject second = history(secondId);
        assertThat(money(second, "penaltyTotal")).isEqualByComparingTo("5.00");
        assertThat(second.getJSONArray("penalties").getJSONObject(0).getString("originAttendanceId"))
                .isEqualTo(originId);
        JSONObject origin = history(originId);
        assertThat(money(second, "totalValue")).as("totalValue e so o servico — a multa vem separada")
                .isEqualByComparingTo(money(origin, "totalValue"));
        assertThat(money(origin, "penaltyTotal")).as("quem gerou a multa nao a carrega").isEqualByComparingTo("0.00");
    }

    @Test
    @Order(40)
    @DisplayName("O atendimento que carregava a multa terminou sem servico: ela volta a pendente")
    void carrierLeftWithoutService_penaltyReleased() throws Exception {
        cancel(secondId); // dentro da tolerancia: sair desta vez e de graca

        JSONObject p = pending();
        assertThat(money(p, "total")).as("so a multa antiga, sem multa nova").isEqualByComparingTo("5.00");
        assertThat(p.getJSONArray("penalties").length()).isEqualTo(1);
    }

    @Test
    @Order(50)
    @DisplayName("Atendimento que carrega a multa e concluido: ela fica paga e nao volta")
    void carrierFinished_penaltyPaid() throws Exception {
        this.thirdId = enter();
        mvc.perform(patch("/attendances/{id}/start", thirdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken))
                .andExpect(status().isOk());
        mvc.perform(patch("/attendances/{id}/finish", thirdId)
                        // pagamento-pix: pago no Finalizar, para o portao nao barrar o mesmo cliente depois
                        .contentType(MediaType.APPLICATION_JSON).content("{\"payment_received\":true}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken))
                .andExpect(status().isOk());

        assertThat(money(pending(), "total")).isEqualByComparingTo("0.00");
        assertThat(money(history(thirdId), "penaltyTotal")).as("o relatorio do corte mostra a multa paga")
                .isEqualByComparingTo("5.00");
    }

    @AfterAll
    void tearDown() {
        penaltyEventRepository.deleteAll();
        attendanceRepository.deleteAll();
        userRepository.deleteAll();
    }
}
