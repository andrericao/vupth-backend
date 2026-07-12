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
 * Integration tests — item #40: intencao de fila com horario-alvo (Opcao B: is_intent + target_time).
 *
 * Modelo: ao entrar com {@code targetTime}, o cliente NAO entra como walk-in comum — entra como
 * INTENCAO. Enquanto o atendimento PREVISTO da sua posicao for ANTES do alvo, ele CEDE a vez a quem
 * chega depois (o slot reservado fica no fim da fila, ordenado por scheduledTime=alvo). Quando o
 * previsto encosta/cruza o alvo, em um EVENTO DE FILA (start/finish/late/fit -> updateQueueEstimates)
 * a intencao e CONFIRMADA automaticamente: vira SCHEDULED normal e trava no inicio para nao ceder a
 * futuros walk-ins. O status permanece SCHEDULED o tempo todo.
 *
 * Regras testadas:
 *  - Ingresso viavel (a fila preve atender ANTES do alvo): 201, is_intent=true, target_time setado,
 *    slot reservado (scheduledTime >= alvo).
 *  - Ingresso inviavel (fila ja preve atender em cima/depois do alvo): 422 QUEUE_TARGET_NOT_FEASIBLE.
 *  - Alvo no passado: 400.
 *  - Cede a vez: walk-in que chega depois sorta a FRENTE da intencao.
 *  - Renormalizacao NAO clobbera o slot da intencao (evento de fila preserva o alvo enquanto cede).
 *  - Confirmacao automatica em evento de fila quando o previsto encosta no alvo.
 *
 * Refeicoes recorrentes removidas no setup (ETA puro = tempo de servico). Cada teste limpa a fila.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QueueIntentIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired UserRepository userRepository;
    @Autowired ServiceItemRepository serviceItemRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;

    private final Faker faker = new Faker();

    String ownerProfToken, professionalId, establishmentId;
    List<UUID> serviceItemIds;

    /** Cliente em fila: id do attendance + token do dono. */
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

        // Remove as refeicoes recorrentes seedadas (almoco/janta): o ETA fica puramente baseado no
        // tempo de servico, tornando a viabilidade do alvo deterministica (ver FitInAttendanceIntegrationTest).
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));

        var s1 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(s1.id()));

        log.info("[QueueIntent] setup — estab={} prof={}", establishmentId, professionalId);
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    /** POST /attendances cru (sem expectativa de status) para um CLIENT novo, com targetTime opcional. */
    private MvcResult postAttendance(String token, String clientId,
                                     OffsetDateTime scheduled, OffsetDateTime target) throws Exception {
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId),
                UUID.fromString(establishmentId),
                UUID.fromString(clientId),
                serviceItemIds,
                AttendanceStatus.SCHEDULED,
                scheduled,
                30,
                scheduled,
                target);
        return mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(mapper.writeValueAsString(request)))
                .andReturn();
    }

    /** Walk-in comum (sem alvo), 201. */
    private Q createWalkin() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        MvcResult r = postAttendance(client.token(), client.id(), OffsetDateTime.now(), null);
        assertThat(r.getResponse().getStatus()).as("walk-in criado").isEqualTo(201);
        String id = new JSONObject(r.getResponse().getContentAsString()).getString("id");
        return new Q(id, client.token(), client.id());
    }

    /** Intencao com horario-alvo, esperando 201. */
    private Q createIntent(int targetMinutesFromNow) throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        OffsetDateTime target = OffsetDateTime.now().plusMinutes(targetMinutesFromNow);
        MvcResult r = postAttendance(client.token(), client.id(), OffsetDateTime.now(), target);
        assertThat(r.getResponse().getStatus()).as("intencao criada").isEqualTo(201);
        String id = new JSONObject(r.getResponse().getContentAsString()).getString("id");
        return new Q(id, client.token(), client.id());
    }

    private void start(String id) throws Exception {
        mvc.perform(patch("/attendances/{id}/start", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk());
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
        attendanceRepository.deleteAll();
    }

    // ─────────────────────────────────────────────────────────────
    // Ingresso
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("Ingresso viavel (fila vazia) — entra como INTENCAO com slot reservado >= alvo")
    void intent_feasible_marksIntentWithSlot() throws Exception {
        // posta direto para inspecionar o response (DTO expoe isIntent + targetTime — item #40)
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        OffsetDateTime target = OffsetDateTime.now().plusMinutes(120);
        MvcResult r = postAttendance(client.token(), client.id(), OffsetDateTime.now(), target);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        JSONObject body = new JSONObject(r.getResponse().getContentAsString());
        assertThat(body.getBoolean("isIntent")).as("DTO expoe isIntent=true").isTrue();
        assertThat(body.isNull("targetTime")).as("DTO expoe targetTime").isFalse();
        Q intent = new Q(body.getString("id"), client.token(), client.id());

        Attendance a = reload(intent.id());
        assertThat(a.isIntent()).as("marcado como intencao").isTrue();
        assertThat(a.getTargetTime()).as("alvo persistido").isNotNull();
        assertThat(a.getStatus()).isEqualTo(AttendanceStatus.SCHEDULED);
        // slot reservado fica no alvo (sem outras intencoes a encaixar) -> bem no futuro
        assertThat(a.getScheduledTime())
                .as("slot reservado ~ alvo (futuro), nao 'agora'")
                .isAfter(OffsetDateTime.now().plusMinutes(60));
    }

    @Test
    @Order(11)
    @DisplayName("Ingresso inviavel — fila ja preve atender em cima/depois do alvo -> 422 QUEUE_TARGET_NOT_FEASIBLE")
    void intent_infeasible_422() throws Exception {
        createWalkin();
        createWalkin();
        createWalkin(); // 3 x 30 = 90 min de fila

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        OffsetDateTime target = OffsetDateTime.now().plusMinutes(10); // previsto (90) >= alvo (10) -> inviavel
        MvcResult r = postAttendance(client.token(), client.id(), OffsetDateTime.now(), target);

        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(r.getResponse().getContentAsString()).contains("QUEUE_TARGET_NOT_FEASIBLE");
    }

    @Test
    @Order(12)
    @DisplayName("Alvo no passado -> 400")
    void intent_pastTarget_400() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        OffsetDateTime past = OffsetDateTime.now().minusMinutes(5);
        MvcResult r = postAttendance(client.token(), client.id(), OffsetDateTime.now(), past);

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
    }

    // ─────────────────────────────────────────────────────────────
    // Cede a vez / renormalizacao
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("Cede a vez — walk-in que chega depois sorta a FRENTE da intencao")
    void intent_yieldsToLaterWalkin() throws Exception {
        Q intent = createIntent(120);
        Q walkin = createWalkin(); // chega depois, mas alvo da intencao esta la na frente -> walk-in vai antes

        assertThat(waitingIndexOf(walkin.id())).as("walk-in na frente").isZero();
        assertThat(waitingIndexOf(intent.id())).as("intencao cede -> atras").isEqualTo(1);
        assertThat(reload(intent.id()).isIntent()).isTrue();
    }

    @Test
    @Order(21)
    @DisplayName("Renormalizacao em evento de fila NAO clobbera o slot da intencao (segue cedendo)")
    void intent_slotSurvivesRenormalization() throws Exception {
        Q intent = createIntent(120);
        OffsetDateTime slotBefore = reload(intent.id()).getScheduledTime();

        Q w1 = createWalkin();
        start(w1.id()); // evento de fila -> updateQueueEstimates renormaliza a fila de espera

        Attendance a = reload(intent.id());
        assertThat(a.isIntent()).as("previsto (~30) ainda antes do alvo (120) -> segue intencao").isTrue();
        assertThat(a.getScheduledTime())
                .as("slot preservado (nao repactuado para 'agora')")
                .isAfter(OffsetDateTime.now().plusMinutes(60));
        assertThat(a.getScheduledTime()).isEqualTo(slotBefore);
    }

    // ─────────────────────────────────────────────────────────────
    // Confirmacao automatica
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("Confirmacao automatica — quando o previsto encosta no alvo, em evento de fila vira SCHEDULED normal")
    void intent_confirmsWhenPredictedReachesTarget() throws Exception {
        Q intent = createIntent(35); // viavel: fila vazia (previsto 0 < 35)
        assertThat(reload(intent.id()).isIntent()).isTrue();

        // Carrega a fila: 2 walk-ins (30+30) empurram o previsto da intencao para ~60 min.
        Q w1 = createWalkin();
        createWalkin();

        // Ainda intencao enquanto nao houve evento que reavalie a confirmacao.
        assertThat(reload(intent.id()).isIntent()).as("sem evento ainda -> segue intencao").isTrue();

        // Evento de fila: o profissional inicia o 1o walk-in -> updateQueueEstimates reavalia.
        // Previsto da intencao = restante do em-progresso (30) + w2 (30) = ~60 >= alvo (35) -> confirma.
        start(w1.id());

        Attendance a = reload(intent.id());
        assertThat(a.isIntent()).as("previsto encostou no alvo -> confirmada").isFalse();
        assertThat(a.getStatus()).isEqualTo(AttendanceStatus.SCHEDULED);
    }

    @AfterAll
    void tearDown() {
        attendanceRepository.deleteAll();
        serviceItemRepository.deleteAll();
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}
