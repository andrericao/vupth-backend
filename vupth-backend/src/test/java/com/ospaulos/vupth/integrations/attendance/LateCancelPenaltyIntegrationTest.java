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
 * Integration tests — item #39 (Fase 1): multa / cancelamento tardio + strikes.
 *
 * Regra: ao cancelar um atendimento SCHEDULED com o ETA da PROPRIA vez a menos de 1h (60 min),
 * registra-se um {@link ClientPenaltyEvent} do tipo {@code LATE_CANCEL} e incrementa-se o contador
 * denormalizado {@code strikeCount} do cliente. Cancelar com ETA >= 1h NAO penaliza. Fase 1 apenas
 * registra e expoe a contagem (via GET /clients/{id}) — sem cobranca nem bloqueio automatico.
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

    /** O proprio cliente cancela sua vez (PUT /attendances/{id}/cancel, clientId vem do JWT). */
    private void cancel(Q q) throws Exception {
        mvc.perform(put("/attendances/{id}/cancel", q.id())
                        .header("Authorization", q.token()))
                .andExpect(status().isOk());
    }

    /** GET /clients/{id} -> strikeCount exposto (item #39). */
    private int strikeCountOf(Q q) throws Exception {
        MvcResult r = mvc.perform(get("/clients/{id}", q.clientId())
                        .header("Authorization", q.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strikeCount").exists())
                .andReturn();
        return new JSONObject(r.getResponse().getContentAsString()).getInt("strikeCount");
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
    @DisplayName("Cancelar na frente da fila (ETA 0 < 1h) -> registra strike LATE_CANCEL + expoe count")
    void cancel_atFront_registersStrike() throws Exception {
        Q q = createWalkin(); // sozinho -> ETA da vez = 0 < 60

        cancel(q);

        List<ClientPenaltyEvent> events = penaltyEventRepository
                .findByClientIdOrderByCreatedAtDesc(UUID.fromString(q.clientId()));
        assertThat(events).as("1 evento de penalidade registrado").hasSize(1);
        assertThat(events.get(0).getType()).isEqualTo(PenaltyType.LATE_CANCEL);
        assertThat(events.get(0).getAttendanceId().toString()).isEqualTo(q.id());
        assertThat(events.get(0).getEtaMinutes()).as("ETA da vez < 60").isLessThan(60);

        assertThat(strikeCountOf(q)).as("strikeCount exposto = 1").isEqualTo(1);
    }

    @Test
    @Order(11)
    @DisplayName("Cancelar com ETA >= 1h (atras de 3 na fila) -> NAO penaliza")
    void cancel_farInQueue_noStrike() throws Exception {
        createWalkin();
        createWalkin();
        createWalkin();        // 3 x 30 = 90 min a frente
        Q q = createWalkin();  // 4o -> ETA da vez = 90 >= 60

        cancel(q);

        assertThat(penaltyEventRepository.countByClientId(UUID.fromString(q.clientId())))
                .as("sem strike quando ETA >= 1h").isZero();
        assertThat(strikeCountOf(q)).as("strikeCount permanece 0").isZero();
    }

    @Test
    @Order(12)
    @DisplayName("Cancelamentos tardios reincidentes acumulam strikes")
    void cancel_repeatedLate_accumulatesStrikes() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);

        // 1o cancelamento tardio (sozinho, ETA 0)
        Q first = createAttendanceFor(client.id(), client.token());
        cancel(first);

        // 2o cancelamento tardio do mesmo cliente (de novo sozinho, ETA 0)
        Q second = createAttendanceFor(client.id(), client.token());
        cancel(second);

        assertThat(penaltyEventRepository.countByClientId(UUID.fromString(client.id())))
                .as("2 eventos").isEqualTo(2);
        assertThat(strikeCountOf(new Q(second.id(), client.token(), client.id())))
                .as("strikeCount acumulado = 2").isEqualTo(2);
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
