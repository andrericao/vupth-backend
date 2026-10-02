package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent;
import com.ospaulos.vupth.domain.client.model.PenaltyType;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — multa por sair da fila (D4).
 *
 * Regra: depois da tolerancia gravada no ingresso, cancelar ou sair da fila com o ETA da PROPRIA vez
 * a menos de 1h registra um {@link ClientPenaltyEvent} do tipo {@code LATE_CANCEL} com o valor
 * (R$ 5 com 30 min ou menos, R$ 3 ate 1h) . Dentro da tolerancia, ou
 * com ETA >= 1h, NAO penaliza. Sem isencao para quem esta sozinho (Andre, 25/09).
 *
 * A tolerancia e de minutos reais; o teste a vence escrevendo {@code tolerance_until} no passado
 * ({@link #expireTolerance}) — o relogio nao e o objeto do teste.
 *
 * Cada atendimento dura 30 min (service item seedado com estimatedMinutes=30). Refeicoes recorrentes
 * removidas no setup -> ETA puro = tempo de servico, tornando a janela de 1h deterministica.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LateCancelPenaltyIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired ObjectMapper mapper;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired ClientPenaltyEventRepository penaltyEventRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired UserRepository userRepository;
    @Autowired ServiceItemRepository serviceItemRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;

    private final Faker faker = new Faker();

    String ownerProfToken, professionalId, establishmentId;
    List<UUID> serviceItemIds;

    /** Cliente em fila: id do attendance + token do dono + clientId. */
    private record Q(String id, String token, String clientId) {}

    @BeforeAll
    void setup() throws Exception {
        var ownerProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerProfToken = ownerProf.token();
        this.professionalId = ownerProf.id();

        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerProfToken);
        this.establishmentId = estab.id();
        // Loja "aberta agora" — walk-in do cliente so entra com a loja aberta (gate BUG5 C1).
        TestUserUtils.openEstablishment(establishmentRepository, establishmentId);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerProfToken, establishmentId, "Owner Professional");
        // 20/09: profissional nasce OFF e quem nao esta em expediente nao recebe fila.
        // Aqui o expediente e cenario, nao o objeto do teste — entao ele chega pelo atalho.
        TestUserUtils.startShift(professionalRepository, establishmentId);

        // ETA puro = tempo de servico (sem refeicoes recorrentes).
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));

        var s1 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(s1.id()));

        log.info("[LateCancel] setup — estab={} prof={}", establishmentId, professionalId);
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    /** Cria um walk-in (cliente registrado novo), 201, e devolve o handle. */
    private Q createWalkin() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId),
                UUID.fromString(establishmentId),
                UUID.fromString(client.id()),
                serviceItemIds,
                AttendanceStatus.SCHEDULED,
                OffsetDateTime.now(),
                30,
                OffsetDateTime.now(),
                null);
        MvcResult r = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token())
                        .content(mapper.writeValueAsString(request)))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as("walk-in criado").isEqualTo(201);
        String id = new JSONObject(r.getResponse().getContentAsString()).getString("id");
        return new Q(id, client.token(), client.id());
    }

    /** Vence a tolerancia de saida: o cliente "esperou" alem da janela gratuita. */
    private void expireTolerance(Q q) {
        var attendance = attendanceRepository.findById(UUID.fromString(q.id())).orElseThrow();
        attendance.setToleranceUntil(OffsetDateTime.now().minusMinutes(1));
        attendanceRepository.save(attendance);
    }

    /** Espera que o cliente le + tolerancia + quanto custaria sair agora. */
    private JSONObject waitTime(Q q) throws Exception {
        MvcResult r = mvc.perform(get("/client-queue/{attendanceId}/wait-time", q.id())
                        .param("clientId", q.clientId())
                        .header("Authorization", q.token()))
                .andExpect(status().isOk())
                .andReturn();
        return new JSONObject(r.getResponse().getContentAsString());
    }

    /** O proprio cliente cancela sua vez (PUT /attendances/{id}/cancel, clientId vem do JWT). */
    private void cancel(Q q) throws Exception {
        // D4: o cliente tocou "Sim" no aviso de R$ 5 (o maior valor possivel) — cobre qualquer multa.
        mvc.perform(put("/attendances/{id}/cancel", q.id())
                        .param("acknowledgedPenalty", "5.00")
                        .header("Authorization", q.token()))
                .andExpect(status().isOk());
    }

    @AfterEach
    void clearQueue() {
        penaltyEventRepository.deleteAll();
        attendanceRepository.deleteAll();
    }

    // ─────────────────────────────────────────────────────────────
    // Testes
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("Cancelar depois da tolerancia, sozinho na fila (ETA 0) -> multa de R$ 5, sem isencao")
    void cancel_atFront_registersPenalty() throws Exception {
        Q q = createWalkin(); // sozinho -> ETA da vez = 0 < 60
        expireTolerance(q);

        cancel(q);

        List<ClientPenaltyEvent> events = penaltyEventRepository
                .findByClientIdOrderByCreatedAtDesc(UUID.fromString(q.clientId()));
        assertThat(events).as("1 evento de penalidade registrado").hasSize(1);
        assertThat(events.get(0).getType()).isEqualTo(PenaltyType.LATE_CANCEL);
        assertThat(events.get(0).getAttendanceId().toString()).isEqualTo(q.id());
        assertThat(events.get(0).getEtaMinutes()).as("ETA da vez < 60").isLessThan(60);
        assertThat(events.get(0).getAmount()).as("30 min ou menos -> R$ 5").isEqualByComparingTo("5.00");
    }

    @Test
    @Order(11)
    @DisplayName("Cancelar com ETA >= 1h (atras de 3 na fila) -> NAO penaliza")
    void cancel_farInQueue_noPenalty() throws Exception {
        createWalkin();
        createWalkin();
        createWalkin();        // 3 x 30 = 90 min a frente
        Q q = createWalkin();  // 4o -> ETA da vez = 90 >= 60
        expireTolerance(q);

        cancel(q);

        assertThat(penaltyEventRepository.countByClientId(UUID.fromString(q.clientId())))
                .as("sem multa quando ETA >= 1h").isZero();
    }

    @Test
    @Order(12)
    @DisplayName("Cancelamentos tardios reincidentes geram uma multa cada")
    void cancel_repeatedLate_onePenaltyEach() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);

        // 1o cancelamento tardio (sozinho, ETA 0)
        Q first = createAttendanceFor(client.id(), client.token());
        expireTolerance(first);
        cancel(first);

        // 2o cancelamento tardio do mesmo cliente (de novo sozinho, ETA 0)
        Q second = createAttendanceFor(client.id(), client.token());
        expireTolerance(second);
        cancel(second);

        assertThat(penaltyEventRepository.countByClientId(UUID.fromString(client.id())))
                .as("2 eventos").isEqualTo(2);
    }

    @Test
    @Order(13)
    @DisplayName("Dentro da tolerancia sair e de graca: o wait-time diz ate quando e 0.00, e o cancelamento nao multa")
    void cancel_withinTolerance_free() throws Exception {
        Q q = createWalkin(); // sozinho -> espera 0 -> tolerancia de 5 min

        JSONObject wait = waitTime(q);
        OffsetDateTime until = OffsetDateTime.parse(wait.getString("toleranceUntil"));
        assertThat(until).as("espera <= 30 -> 5 min de saida gratuita")
                .isBetween(OffsetDateTime.now().plusMinutes(4), OffsetDateTime.now().plusMinutes(5));
        assertThat(new BigDecimal(wait.get("exitPenaltyValue").toString())).isEqualByComparingTo("0.00");

        cancel(q);

        assertThat(penaltyEventRepository.countByClientId(UUID.fromString(q.clientId())))
                .as("saiu dentro da tolerancia").isZero();
    }

    @Test
    @Order(14)
    @DisplayName("Vencida a tolerancia, o wait-time mostra o valor ANTES da saida — o mesmo que e cobrado")
    void waitTime_afterTolerance_showsWhatWillBeCharged() throws Exception {
        Q q = createWalkin();
        expireTolerance(q);

        assertThat(new BigDecimal(waitTime(q).get("exitPenaltyValue").toString()))
                .as("o aviso Sim / Nao mostra este valor").isEqualByComparingTo("5.00");

        cancel(q);

        assertThat(penaltyEventRepository.findByClientIdOrderByCreatedAtDesc(UUID.fromString(q.clientId()))
                .get(0).getAmount()).isEqualByComparingTo("5.00");
    }

    @Test
    @Order(15)
    @DisplayName("Sair da fila (leave-queue) e o mesmo ato que cancelar: depois da tolerancia, multa")
    void leaveQueue_afterTolerance_charged() throws Exception {
        Q q = createWalkin();
        expireTolerance(q);

        mvc.perform(delete("/attendances/{id}/leave-queue", q.id())
                        .param("acknowledgedPenalty", "5.00")
                        .header("Authorization", q.token()))
                .andExpect(status().isOk());

        List<ClientPenaltyEvent> events = penaltyEventRepository
                .findByClientIdOrderByCreatedAtDesc(UUID.fromString(q.clientId()));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getAmount()).isEqualByComparingTo("5.00");
    }

    @Test
    @Order(20)
    @DisplayName("Ciencia obrigatoria: sem o 'Sim', a saida com multa NAO acontece — 409 com o valor")
    void cancel_withoutAcknowledgement_conflictWithValue() throws Exception {
        Q q = createWalkin();
        expireTolerance(q);

        mvc.perform(put("/attendances/{id}/cancel", q.id())
                        .header("Authorization", q.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXIT_PENALTY_NOT_ACKNOWLEDGED"))
                .andExpect(jsonPath("$.exitPenaltyValue").value(5.00));

        assertThat(attendanceRepository.findById(UUID.fromString(q.id())).orElseThrow().getStatus())
                .as("continua na fila").isEqualTo(AttendanceStatus.SCHEDULED);
        assertThat(penaltyEventRepository.countByClientId(UUID.fromString(q.clientId()))).isZero();
    }

    @Test
    @Order(21)
    @DisplayName("Confirmar valor MENOR que a multa de agora nao vale: 409 de novo (leave-queue)")
    void leaveQueue_acknowledgedLess_conflict() throws Exception {
        Q q = createWalkin();
        expireTolerance(q);

        mvc.perform(delete("/attendances/{id}/leave-queue", q.id())
                        .param("acknowledgedPenalty", "3.00")
                        .header("Authorization", q.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.exitPenaltyValue").value(5.00));

        assertThat(penaltyEventRepository.countByClientId(UUID.fromString(q.clientId()))).isZero();
    }

    @Test
    @Order(22)
    @DisplayName("Com o 'Sim' a saida acontece e a multa guarda o instante da ciencia")
    void cancel_acknowledged_recordsAcknowledgement() throws Exception {
        Q q = createWalkin();
        expireTolerance(q);

        cancel(q);

        ClientPenaltyEvent event = penaltyEventRepository
                .findByClientIdOrderByCreatedAtDesc(UUID.fromString(q.clientId())).get(0);
        assertThat(event.getAmount()).isEqualByComparingTo("5.00");
        assertThat(event.getAcknowledgedAt()).as("o toque do cliente fica registrado").isNotNull();
    }

    @Test
    @Order(23)
    @DisplayName("Sem multa (dentro da tolerancia) a saida nao pede ciencia")
    void cancel_noPenalty_noAcknowledgementNeeded() throws Exception {
        Q q = createWalkin();

        mvc.perform(put("/attendances/{id}/cancel", q.id())
                        .header("Authorization", q.token()))
                .andExpect(status().isOk());
    }

    /** Cria um atendimento walk-in para um cliente EXISTENTE (reaproveita o mesmo usuario). */
    private Q createAttendanceFor(String clientId, String token) throws Exception {
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId),
                UUID.fromString(establishmentId),
                UUID.fromString(clientId),
                serviceItemIds,
                AttendanceStatus.SCHEDULED,
                OffsetDateTime.now(),
                30,
                OffsetDateTime.now(),
                null);
        MvcResult r = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(mapper.writeValueAsString(request)))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        String id = new JSONObject(r.getResponse().getContentAsString()).getString("id");
        return new Q(id, token, clientId);
    }

    @AfterAll
    void tearDown() {
        penaltyEventRepository.deleteAll();
        attendanceRepository.deleteAll();
        serviceItemRepository.deleteAll();
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}
