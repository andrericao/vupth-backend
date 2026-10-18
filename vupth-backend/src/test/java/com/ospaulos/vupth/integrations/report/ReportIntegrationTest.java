package com.ospaulos.vupth.integrations.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — domain: Report (relatorios agregados #4 + #5)
 *
 * Cobre:
 *   GET /establishment/{id}/reports   (OWNER) — totais + breakdown por profissional
 *   GET /professional/me/reports      (PROFESSIONAL) — totais do proprio profissional
 *
 * Ambiente: owner que tambem e profissional (solo shop) finaliza 1 atendimento,
 * gerando uma base FINISHED com receita para os agregados.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired ObjectMapper mapper;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.ospaulos.vupth.domain.client.repository.ClientRepository clientRepository;
    @Autowired com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository penaltyEventRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired UserRepository userRepository;
    @Autowired ServiceItemRepository serviceItemRepository;

    private final Faker faker = new Faker();

    String ownerProfToken, ownerProfId, professionalId, establishmentId;
    String clientToken, clientId;
    String otherOwnerToken;
    List<UUID> serviceItemIds;

    @BeforeAll
    void setup() throws Exception {
        log.info("[Report] setup — montando ambiente com 1 atendimento FINISHED");

        var ownerProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerProfId = ownerProf.id();
        this.ownerProfToken = ownerProf.token();

        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerProfToken);
        this.establishmentId = estab.id();
        // Loja "aberta agora" — walk-in do cliente so entra com a loja aberta (gate BUG5 C1).
        TestUserUtils.openEstablishment(establishmentRepository, establishmentId);

        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerProfToken, establishmentId, "Owner Professional");
        // 20/09: profissional nasce OFF e quem nao esta em expediente nao recebe fila.
        // Aqui o expediente e cenario, nao o objeto do teste — entao ele chega pelo atalho.
        TestUserUtils.startShift(professionalRepository, establishmentId);
        this.professionalId = ownerProfId; // MapsId: professional.id == baseUser.id

        var s1 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.CORTE_MAQUINA, "Corte Degrade", BigDecimal.valueOf(30));
        var s2 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.BARBA, "Barba", BigDecimal.valueOf(20));
        this.serviceItemIds = List.of(UUID.fromString(s1.id()), UUID.fromString(s2.id()));

        // Receita LIQUIDA: os dois servicos entram na regra de preco. O atendimento congela
        // 31,90 + 21,90 = 53,80, dos quais o profissional recebe 30,00 + 20,00 = 50,00.
        priceService(s1.id(), "32.90", "30.00", "30.00");
        priceService(s2.id(), "21.90", "20.00", "20.00");

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientId = client.id();
        this.clientToken = client.token();

        // Owner de outro establishment — para validar isolamento (403)
        var otherOwner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        TestUserUtils.becomeOwner(mvc, mapper, faker, otherOwner.token());
        this.otherOwnerToken = otherOwner.token();

        // Cria → start → finish 1 atendimento, gerando base FINISHED com receita
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId),
                UUID.fromString(establishmentId),
                UUID.fromString(clientId),
                serviceItemIds,
                AttendanceStatus.SCHEDULED,
                OffsetDateTime.now().plusMinutes(5),
                30,
                OffsetDateTime.now());

        MvcResult created = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        String attId = new JSONObject(created.getResponse().getContentAsString()).getString("id");

        mvc.perform(patch("/attendances/{id}/start", attId)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk());
        mvc.perform(patch("/attendances/{id}/finish", attId)
                        // pagamento-pix: pago no Finalizar, para o portao nao barrar o mesmo cliente depois
                        .contentType(MediaType.APPLICATION_JSON).content("{\"payment_received\":true}")
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk());

        log.info("[Report] setup — atendimento {} FINISHED, ambiente pronto", attId);
    }

    private void priceService(String itemId, String price, String proReceives, String basePrice) throws Exception {
        mvc.perform(patch("/professional-queue/{profId}/service-items/{itemId}", ownerProfId, itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken)
                        .content(String.format("{\"price\":%s,\"pro_receives\":%s,\"base_price\":%s}", price, proReceives, basePrice)))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────────────────────
    // GET /establishment/{id}/reports
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("GET /establishment/{id}/reports — 200 owner ve totais + breakdown por profissional")
    void establishmentReport_happyPath() throws Exception {
        mvc.perform(get("/establishment/{id}/reports", establishmentId)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAttendances").value(1))
                .andExpect(jsonPath("$.totalRevenue").exists())
                .andExpect(jsonPath("$.avgDurationMinutes").exists())
                .andExpect(jsonPath("$.topService").exists())
                .andExpect(jsonPath("$.topProfessional").exists())
                .andExpect(jsonPath("$.professionals").isArray())
                .andExpect(jsonPath("$.professionals[0].id").value(professionalId))
                .andExpect(jsonPath("$.professionals[0].name").exists())
                .andExpect(jsonPath("$.professionals[0].attendances").value(1))
                .andExpect(jsonPath("$.professionals[0].revenue").exists())
                .andExpect(jsonPath("$.totalRevenue").value(54.80))
                .andExpect(jsonPath("$.netRevenue").value(50.00))
                .andExpect(jsonPath("$.attendancesWithoutSplit").value(0))
                .andExpect(jsonPath("$.professionals[0].netRevenue").value(50.00))
                .andExpect(jsonPath("$.notRealized.client_cancelled").value(0))
                .andExpect(jsonPath("$.notRealized.professional_removed").value(0))
                .andExpect(jsonPath("$.notRealized.removed_for_lateness").value(0))
                .andExpect(jsonPath("$.finishedLate").value(0))
                .andDo(print());
    }

    @Test
    @Order(11)
    @DisplayName("GET /establishment/{id}/reports?dateFrom=hoje&dateTo=hoje — 200 periodo inclui o atendimento")
    void establishmentReport_periodToday() throws Exception {
        // Dia da LOJA (backlog #33): com LocalDate.now() + janela em UTC este teste falhava
        // entre 21h e meia-noite em Sao Paulo — o atendimento recem-criado ja estava no dia
        // UTC seguinte e caia fora do teto da janela.
        String today = BusinessClock.today().toString();
        mvc.perform(get("/establishment/{id}/reports", establishmentId)
                        .param("dateFrom", today)
                        .param("dateTo", today)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAttendances").value(1))
                .andDo(print());
    }

    @Test
    @Order(12)
    @DisplayName("GET /establishment/{id}/reports?dateFrom=2099 — 200 periodo futuro zera os totais")
    void establishmentReport_futurePeriodEmpty() throws Exception {
        mvc.perform(get("/establishment/{id}/reports", establishmentId)
                        .param("dateFrom", "2099-01-01")
                        .param("dateTo", "2099-12-31")
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAttendances").value(0))
                .andExpect(jsonPath("$.topService").doesNotExist())
                .andExpect(jsonPath("$.topProfessional").doesNotExist())
                .andExpect(jsonPath("$.professionals").isEmpty())
                .andDo(print());
    }

    @Test
    @Order(13)
    @DisplayName("GET /establishment/{id}/reports — 401 sem token")
    void establishmentReport_noToken_401() throws Exception {
        mvc.perform(get("/establishment/{id}/reports", establishmentId))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(14)
    @DisplayName("GET /establishment/{id}/reports — 403 client sem ROLE_OWNER")
    void establishmentReport_clientToken_403() throws Exception {
        mvc.perform(get("/establishment/{id}/reports", establishmentId)
                        .header("Authorization", clientToken))
                .andExpect(status().isForbidden())
                .andDo(print());
    }

    @Test
    @Order(15)
    @DisplayName("GET /establishment/{id}/reports — 403 owner de outro establishment nao acessa")
    void establishmentReport_otherOwner_forbidden() throws Exception {
        mvc.perform(get("/establishment/{id}/reports", establishmentId)
                        .header("Authorization", otherOwnerToken))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isIn(403, 404))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // GET /professional/me/reports
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("GET /professional/me/reports — 200 profissional ve os proprios totais")
    void professionalReport_happyPath() throws Exception {
        mvc.perform(get("/professional/me/reports")
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAttendances").value(1))
                .andExpect(jsonPath("$.totalRevenue").exists())
                .andExpect(jsonPath("$.avgDurationMinutes").exists())
                .andExpect(jsonPath("$.topService").exists())
                .andExpect(jsonPath("$.totalRevenue").value(54.80))
                .andExpect(jsonPath("$.netRevenue").value(50.00))
                .andExpect(jsonPath("$.attendancesWithoutSplit").value(0))
                .andDo(print());
    }

    @Test
    @Order(21)
    @DisplayName("GET /professional/me/reports?dateFrom=2099 — 200 periodo futuro zera os totais")
    void professionalReport_futurePeriodEmpty() throws Exception {
        mvc.perform(get("/professional/me/reports")
                        .param("dateFrom", "2099-01-01")
                        .param("dateTo", "2099-12-31")
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAttendances").value(0))
                .andExpect(jsonPath("$.topService").doesNotExist())
                .andDo(print());
    }

    @Test
    @Order(22)
    @DisplayName("GET /professional/me/reports — 401 sem token")
    void professionalReport_noToken_401() throws Exception {
        mvc.perform(get("/professional/me/reports"))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(23)
    @DisplayName("GET /professional/me/reports — 403 client sem ROLE_PROFESSIONAL")
    void professionalReport_clientToken_403() throws Exception {
        mvc.perform(get("/professional/me/reports")
                        .header("Authorization", clientToken))
                .andExpect(status().isForbidden())
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // Expediente real (Partner S4)
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("relatorios — expediente do dia: em andamento sem fim; depois de ir para Fora, com fim")
    void reports_workdays() throws Exception {
        String today = BusinessClock.today().toString();

        // Em expediente desde o setup: o dia aparece com inicio e sem fim.
        mvc.perform(get("/professional/me/reports")
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workdays.length()").value(1))
                .andExpect(jsonPath("$.workdays[0].date").value(today))
                .andExpect(jsonPath("$.workdays[0].start").exists())
                .andExpect(jsonPath("$.workdays[0].end").doesNotExist());

        mvc.perform(patch("/professional/me/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken)
                        .content("{\"status\":\"OFF\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/professional/me/reports")
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workdays[0].end").exists());

        mvc.perform(get("/establishment/{id}/reports", establishmentId)
                        .param("dateFrom", today)
                        .param("dateTo", today)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workdays.length()").value(1))
                .andExpect(jsonPath("$.workdays[0].start").exists())
                .andExpect(jsonPath("$.workdays[0].end").exists())
                .andDo(print());

        // Periodo sem turno: lista vazia.
        mvc.perform(get("/establishment/{id}/reports", establishmentId)
                        .param("dateFrom", "2099-01-01")
                        .param("dateTo", "2099-12-31")
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workdays").isEmpty());
    }

    @Test
    @Order(60)
    @DisplayName("GET /owner/me/reports — o dono ve o total, cada loja e a serie; loja alheia 403; sem periodo zera")
    void ownerReport() throws Exception {
        // a segunda loja do dono, sem atendimento
        TestUserUtils.becomeOwner(mvc, mapper, new Faker(), ownerProfToken);

        mvc.perform(get("/owner/me/reports").param("groupBy", "month").header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total.finished").value(1))
                .andExpect(jsonPath("$.total.gross_revenue").value(54.80))
                .andExpect(jsonPath("$.total.professionals_net").value(50.00))
                .andExpect(jsonPath("$.total.gateway_fee").isNumber())
                .andExpect(jsonPath("$.total.app_fee").isNumber())
                .andExpect(jsonPath("$.total.cancelled").value(0))
                .andExpect(jsonPath("$.total.penalties").doesNotExist())
                .andExpect(jsonPath("$.total.not_realized.client_cancelled").value(0))
                .andExpect(jsonPath("$.total.not_realized.professional_removed").value(0))
                .andExpect(jsonPath("$.stores[0].summary.not_realized.removed_for_lateness").value(0))
                .andExpect(jsonPath("$.total.finished_late").value(0))
                .andExpect(jsonPath("$.stores.length()").value(2))
                .andExpect(jsonPath("$.stores[?(@.id=='" + establishmentId + "')].summary.finished").value(1))
                .andExpect(jsonPath("$.series.length()").value(1))
                .andExpect(jsonPath("$.series[0].summary.finished").value(1));

        // so a loja pedida; a de outro dono e 403
        mvc.perform(get("/owner/me/reports").param("establishmentId", establishmentId)
                        .header("Authorization", ownerProfToken))
                .andExpect(jsonPath("$.stores.length()").value(1));
        var stranger = TestUserUtils.registerAndLoginUser(mvc, mapper, new Faker());
        TestUserUtils.becomeOwner(mvc, mapper, new Faker(), stranger.token());
        mvc.perform(get("/owner/me/reports").param("establishmentId", establishmentId)
                        .header("Authorization", stranger.token()))
                .andExpect(status().isForbidden());

        // periodo futuro: zera; quem nao e dono: 403
        mvc.perform(get("/owner/me/reports").param("dateFrom", "2099-01-01").param("dateTo", "2099-12-31")
                        .param("groupBy", "week").header("Authorization", ownerProfToken))
                .andExpect(jsonPath("$.total.finished").value(0))
                .andExpect(jsonPath("$.series").isEmpty());
        var plain = TestUserUtils.registerAndLoginUser(mvc, mapper, new Faker());
        mvc.perform(get("/owner/me/reports").header("Authorization", plain.token())).andExpect(status().isForbidden());
    }

    @Test
    @Order(70)
    @DisplayName("relatorio-sem-multa: os tres motivos na contagem do dono e na lista, sem valor de multa")
    void notRealizedWithReason() throws Exception {
        // o unico atendimento da loja passa por tres estados; cada um e um motivo
        var attendance = attendanceRepository.findAll().stream().findFirst().orElseThrow();
        var client = clientRepository.findById(attendance.getClient().getId()).orElseThrow();

        jdbc.update("UPDATE attendance SET status = 'CANCELLED' WHERE id = ?", attendance.getId());
        expectReason("CLIENT_CANCELLED", 1, 0, 0);

        jdbc.update("UPDATE attendance SET status = 'REMOVED' WHERE id = ?", attendance.getId());
        expectReason("PROFESSIONAL_REMOVED", 0, 1, 0);
        // a mesma contagem, sem valor de multa, nos relatorios da loja e do profissional
        mvc.perform(get("/establishment/{id}/reports", establishmentId).header("Authorization", ownerProfToken))
                .andExpect(jsonPath("$.totalAttendances").value(0))
                .andExpect(jsonPath("$.notRealized.professional_removed").value(1))
                .andExpect(jsonPath("$.penalties").doesNotExist());
        mvc.perform(get("/professional/me/reports").header("Authorization", ownerProfToken))
                .andExpect(jsonPath("$.notRealized.professional_removed").value(1))
                .andExpect(jsonPath("$.finishedLate").value(0));

        penaltyEventRepository.save(new com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent(
                client, com.ospaulos.vupth.domain.client.model.PenaltyType.NO_SHOW, attendance.getId(), 0,
                new java.math.BigDecimal("5.00")));
        expectReason("REMOVED_FOR_LATENESS", 0, 0, 1);

        // concluido com atraso: nao e "nao realizado", leva a observacao
        jdbc.update("UPDATE attendance SET status = 'FINISHED' WHERE id = ?", attendance.getId());
        jdbc.update("UPDATE client_penalty_event SET type = 'LATE_ARRIVAL' WHERE attendance_id = ?", attendance.getId());
        mvc.perform(get("/owner/me/reports").param("establishmentId", establishmentId)
                        .header("Authorization", ownerProfToken))
                .andExpect(jsonPath("$.total.finished").value(1))
                .andExpect(jsonPath("$.total.finished_late").value(1))
                .andExpect(jsonPath("$.total.not_realized.removed_for_lateness").value(0));
        mvc.perform(get("/establishment/{id}/reports", establishmentId).header("Authorization", ownerProfToken))
                .andExpect(jsonPath("$.finishedLate").value(1));
        mvc.perform(get("/attendances").param("establishmentId", establishmentId)
                        .header("Authorization", ownerProfToken))
                .andExpect(jsonPath("$[0].late_arrival").value(true))
                .andExpect(jsonPath("$[0].not_realized_reason").doesNotExist());
    }

    private void expectReason(String reason, int cancelled, int removed, int late) throws Exception {
        mvc.perform(get("/owner/me/reports").param("establishmentId", establishmentId)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total.finished").value(0))
                .andExpect(jsonPath("$.total.not_realized.client_cancelled").value(cancelled))
                .andExpect(jsonPath("$.total.not_realized.professional_removed").value(removed))
                .andExpect(jsonPath("$.total.not_realized.removed_for_lateness").value(late))
                .andExpect(jsonPath("$.series[0].summary.not_realized.client_cancelled").value(cancelled))
                .andExpect(jsonPath("$.total.penalties").doesNotExist());
        mvc.perform(get("/attendances").param("establishmentId", establishmentId)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].not_realized_reason").value(reason))
                .andExpect(jsonPath("$[0].penalties").doesNotExist())
                .andExpect(jsonPath("$[0].penaltyTotal").doesNotExist());
    }

    @AfterAll
    void tearDown() {
        log.info("[Report] tearDown — limpando dados");
        attendanceRepository.deleteAll();
        serviceItemRepository.deleteAll();
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}
