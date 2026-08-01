package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — item #38: encaixe com equalizacao de tempo (fit-in).
 *
 * Regras testadas:
 *  - PATCH /attendances/{id}/fit-in?aheadCount=N: o atendimento e movido para logo apos N clientes
 *    (vira o N+1-esimo), marcado isFit com fitAheadLength = quantos realmente ficaram a frente.
 *  - Ramp: ao encaixar, o tempo do encaixe (D) NAO entra de uma vez no ETA dos de tras — entra em
 *    fatias de D/N a cada cliente que sai da frente (cancelado/finalizado). A estimativa sobe sem
 *    solavanco e, quando o encaixe chega na boca da fila, contribui o D inteiro.
 *  - PATCH /undo-fit: deixa de ser encaixe (volta a contribuir o tempo cheio), mantendo a posicao.
 *  - Erros: status invalido (400), aheadCount negativo (400), undo de nao-encaixe (400),
 *    profissional errado (403/404), sem token (401), token CLIENT sem role PROFESSIONAL (403),
 *    attendance inexistente (404).
 *
 * Cada teste comeca com a fila LIMPA (ver @AfterEach), entao as assertivas de tempo total sao
 * ABSOLUTAS. Todos os service items duram 30 min (D=30). Sem ninguem em atendimento, o ETA total
 * da fila usa a contribuicao rampada do encaixe (QueueUtils.effectiveEstimatedMinutes).
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FitInAttendanceIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired UserRepository userRepository;
    @Autowired ServiceItemRepository serviceItemRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;

    private final Faker faker = new Faker();

    String ownerProfToken, professionalId, establishmentId;
    String clientToken;          // CLIENT puro (sem role PROFESSIONAL) — tambem usado para GET da fila
    String anotherProfToken;     // PROFESSIONAL de OUTRA loja (violacao de ownership)
    List<UUID> serviceItemIds;

    /** Cliente em fila: id do attendance + token do dono (para cancelar / consultar wait-time). */
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

        // quick-create seeda refeicoes recorrentes (almoco 12:00 / janta 18:00 UTC). Elas sao TRAVADAS
        // no ingresso e entram no ETA individual (lockedClientWait), o que tornaria os totais ABSOLUTOS
        // deste teste dependentes da hora do relogio (flaky quando a suite roda perto de uma refeicao).
        // Removemos as refeicoes para o ETA ficar puramente baseado no tempo de servico (premissa da classe).
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));

        var s1 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(s1.id()));

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientToken = client.token();

        var anotherUser = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var anotherEstab = TestUserUtils.becomeOwner(mvc, mapper, faker, anotherUser.token());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, anotherUser.token(), anotherEstab.id());
        this.anotherProfToken = anotherUser.token();

        log.info("[FitIn] setup — estab={} prof={}", establishmentId, professionalId);
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    /** Cria um attendance SCHEDULED (30 min) para um CLIENT novo, no scheduledTime dado. */
    private Q createScheduled(int minutesFromNow) throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId),
                UUID.fromString(establishmentId),
                UUID.fromString(client.id()),
                serviceItemIds,
                AttendanceStatus.SCHEDULED,
                OffsetDateTime.now().plusMinutes(minutesFromNow),
                30,
                OffsetDateTime.now().plusMinutes(minutesFromNow));
        MvcResult result = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token())
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        String id = new JSONObject(result.getResponse().getContentAsString()).getString("id");
        return new Q(id, client.token(), client.id());
    }

    private MvcResult fitIn(String id, int aheadCount, String token) throws Exception {
        return mvc.perform(patch("/attendances/{id}/fit-in", id)
                        .param("aheadCount", String.valueOf(aheadCount))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token))
                .andDo(print())
                .andReturn();
    }

    private void cancel(Q q) throws Exception {
        mvc.perform(put("/attendances/{id}/cancel", q.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", q.token()))
                .andExpect(status().isOk());
    }

    /** Total estimado da fila (ETA agregado) via GET da fila (sem ninguem em atendimento). */
    private int queueTotalEstimated() throws Exception {
        MvcResult r = mvc.perform(get("/client-queue/{professionalId}/queue", professionalId)
                        .param("establishmentId", establishmentId)
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andReturn();
        return new JSONObject(r.getResponse().getContentAsString()).getInt("totalEstimatedMinutes");
    }

    /** Espera estimada individual do cliente (usa lockedClientWait — caminho que o cliente ve). */
    private int clientWait(Q q) throws Exception {
        MvcResult r = mvc.perform(get("/client-queue/{attendanceId}/wait-time", q.id())
                        .param("clientId", q.clientId())
                        .header("Authorization", q.token()))
                .andExpect(status().isOk())
                .andReturn();
        return new JSONObject(r.getResponse().getContentAsString()).getInt("estimatedWaitingTime");
    }

    private Attendance reload(String id) {
        return attendanceRepository.findById(UUID.fromString(id)).orElseThrow();
    }

    /** Posicao (indice) do attendance na fila de espera (SCHEDULED + LATE), -1 se ausente. */
    private int waitingIndexOf(String id) {
        List<Attendance> waiting = attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(
                UUID.fromString(professionalId), UUID.fromString(establishmentId));
        for (int i = 0; i < waiting.size(); i++) {
            if (waiting.get(i).getId().toString().equals(id)) return i;
        }
        return -1;
    }

    @AfterEach
    void clearQueue() {
        // Fila limpa por teste -> totais ABSOLUTOS deterministicos.
        attendanceRepository.deleteAll();
    }

    // ─────────────────────────────────────────────────────────────
    // Posicionamento
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("PATCH /fit-in?aheadCount=2 — atendimento vai para o indice 2 e vira encaixe (isFit, length=2)")
    void fitIn_positionsAfterN() throws Exception {
        createScheduled(10);             // index 0
        createScheduled(11);             // index 1
        createScheduled(12);             // index 2
        Q last = createScheduled(13);    // index 3 (sera encaixado)

        fitIn(last.id(), 2, ownerProfToken);

        assertThat(waitingIndexOf(last.id())).as("encaixado apos 2 clientes").isEqualTo(2);
        Attendance fit = reload(last.id());
        assertThat(fit.isFit()).isTrue();
        assertThat(fit.getFitAheadLength()).isEqualTo(2);
        assertThat(fit.getStatus()).isEqualTo(AttendanceStatus.SCHEDULED);
    }

    @Test
    @Order(11)
    @DisplayName("PATCH /fit-in?aheadCount=0 — vai para a frente da fila, sem ramp (length=0)")
    void fitIn_toFront_noRamp() throws Exception {
        createScheduled(20);
        createScheduled(21);
        Q last = createScheduled(22);

        fitIn(last.id(), 0, ownerProfToken);

        assertThat(waitingIndexOf(last.id())).isZero();
        assertThat(reload(last.id()).getFitAheadLength()).isZero();
    }

    @Test
    @Order(12)
    @DisplayName("PATCH /fit-in com aheadCount alem do tamanho — vai pro fim, length clampado")
    void fitIn_beyondEnd_clamped() throws Exception {
        Q first = createScheduled(30);
        createScheduled(31);

        fitIn(first.id(), 99, ownerProfToken);

        // so existe 1 outro cliente -> fica atras dele (indice 1), length clampado em 1
        assertThat(waitingIndexOf(first.id())).isEqualTo(1);
        assertThat(reload(first.id()).getFitAheadLength()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────
    // Ramp (equalizacao de tempo)
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("Ramp via ETA total: encaixe entra com 0 e sobe 1 fatia (D/length) a cada cliente que sai da frente")
    void ramp_climbsBySlicePerDeparture() throws Exception {
        Q q0 = createScheduled(40);
        Q q1 = createScheduled(41);
        createScheduled(42);                 // q2 (fica de tras)
        Q q3 = createScheduled(43);          // sera encaixado apos 2 -> length=2, D=30, fatia=15

        assertThat(queueTotalEstimated()).as("4 clientes x 30").isEqualTo(120);

        fitIn(q3.id(), 2, ownerProfToken);
        // [q0, q1, q3(fit, contribui 0), q2] -> 30 + 30 + 0 + 30
        assertThat(queueTotalEstimated()).as("ao encaixar, o tempo do encaixe NAO entra de uma vez").isEqualTo(90);

        cancel(q0);
        // [q1, q3(idx1 -> 1 fatia = 15), q2] -> 30 + 15 + 30
        assertThat(queueTotalEstimated()).as("1 cliente saiu da frente -> +1 fatia").isEqualTo(75);

        cancel(q1);
        // [q3(idx0 -> boca da fila = 30 cheio), q2] -> 30 + 30
        assertThat(queueTotalEstimated()).as("encaixe na boca da fila -> contribui D cheio").isEqualTo(60);
    }

    @Test
    @Order(21)
    @DisplayName("Ramp: o cliente de tras NAO ve salto no proprio ETA no instante do encaixe")
    void ramp_noJumpForClientBehindAtInsertion() throws Exception {
        createScheduled(50);                 // q0
        createScheduled(51);                 // q1
        Q behind = createScheduled(52);      // q2 (fica de tras do encaixe)
        Q toFit = createScheduled(53);       // encaixado apos 2 (entre q1 e q2)

        int before = clientWait(behind);     // 2 clientes a frente (q0,q1) x 30 = 60
        assertThat(before).isEqualTo(60);

        fitIn(toFit.id(), 2, ownerProfToken);

        // agora q2 tem [q0, q1, encaixe(contribui 0)] a frente -> continua 60, sem solavanco
        assertThat(clientWait(behind))
                .as("o encaixe nao adiciona seu tempo de uma vez no ETA de quem esta atras")
                .isEqualTo(before);
    }

    // ─────────────────────────────────────────────────────────────
    // Undo
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("PATCH /undo-fit — deixa de ser encaixe e volta a contribuir o tempo cheio")
    void undoFit_restoresFullContribution() throws Exception {
        createScheduled(60);
        createScheduled(61);
        Q toFit = createScheduled(62);

        fitIn(toFit.id(), 2, ownerProfToken);
        assertThat(reload(toFit.id()).isFit()).isTrue();
        // [q0, q1, fit(0)] -> 30 + 30 + 0
        assertThat(queueTotalEstimated()).isEqualTo(60);

        mvc.perform(patch("/attendances/{id}/undo-fit", toFit.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andDo(print());

        Attendance after = reload(toFit.id());
        assertThat(after.isFit()).isFalse();
        assertThat(after.getFitAheadLength()).isNull();
        // volta a contribuir cheio -> 90
        assertThat(queueTotalEstimated()).isEqualTo(90);
    }

    // ─────────────────────────────────────────────────────────────
    // Erros
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    @DisplayName("PATCH /fit-in — 400 status invalido (attendance IN_PROGRESS nao pode ser encaixado)")
    void fitIn_invalidStatus_400() throws Exception {
        Q q = createScheduled(70);
        createScheduled(71);

        mvc.perform(patch("/attendances/{id}/start", q.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk());

        fitIn(q.id(), 1, ownerProfToken);
        assertThat(reload(q.id()).isFit()).isFalse();

        // o resultado HTTP do fit-in deve ser 400
        mvc.perform(patch("/attendances/{id}/fit-in", q.id())
                        .param("aheadCount", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isBadRequest());

        // cleanup: libera o profissional
        mvc.perform(patch("/attendances/{id}/finish", q.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk());
    }

    @Test
    @Order(41)
    @DisplayName("PATCH /fit-in — 400 aheadCount negativo")
    void fitIn_negativeAheadCount_400() throws Exception {
        Q q = createScheduled(80);
        createScheduled(81);

        mvc.perform(patch("/attendances/{id}/fit-in", q.id())
                        .param("aheadCount", "-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isBadRequest())
                .andDo(print());
    }

    @Test
    @Order(42)
    @DisplayName("PATCH /undo-fit — 400 quando o attendance nao e encaixe")
    void undoFit_notFit_400() throws Exception {
        Q q = createScheduled(90);

        mvc.perform(patch("/attendances/{id}/undo-fit", q.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isBadRequest())
                .andDo(print());
    }

    @Test
    @Order(43)
    @DisplayName("PATCH /fit-in — 403/404 profissional de outra loja")
    void fitIn_wrongProfessional_forbidden() throws Exception {
        Q q = createScheduled(100);
        createScheduled(101);

        mvc.perform(patch("/attendances/{id}/fit-in", q.id())
                        .param("aheadCount", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", anotherProfToken))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(403, 404))
                .andDo(print());

        assertThat(reload(q.id()).isFit()).isFalse();
    }

    @Test
    @Order(44)
    @DisplayName("PATCH /fit-in — 401 sem token")
    void fitIn_noToken_401() throws Exception {
        mvc.perform(patch("/attendances/{id}/fit-in", UUID.randomUUID())
                        .param("aheadCount", "1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(45)
    @DisplayName("PATCH /fit-in — 403 token CLIENT nao tem role PROFESSIONAL")
    void fitIn_clientToken_403() throws Exception {
        Q q = createScheduled(110);
        createScheduled(111);

        mvc.perform(patch("/attendances/{id}/fit-in", q.id())
                        .param("aheadCount", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken))
                .andExpect(status().isForbidden())
                .andDo(print());
    }

    @Test
    @Order(46)
    @DisplayName("PATCH /fit-in — 404 attendance inexistente")
    void fitIn_notFound_404() throws Exception {
        mvc.perform(patch("/attendances/{id}/fit-in", UUID.randomUUID())
                        .param("aheadCount", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isNotFound())
                .andDo(print());
    }

    @AfterAll
    void tearDown() {
        attendanceRepository.deleteAll();
        serviceItemRepository.deleteAll();
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}
