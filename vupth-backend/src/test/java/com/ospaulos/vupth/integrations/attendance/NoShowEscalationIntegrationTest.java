package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceGuestRequest;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent;
import com.ospaulos.vupth.domain.client.model.PenaltyType;
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
 * Integration tests — item #39 (escalada de no-show).
 *
 * Regra: um cliente que ja estava LATE (#19, caiu de posicao) e nao compareceu ao ser chamado de
 * novo pode ser removido da fila pelo profissional via {@code PATCH /attendances/{id}/no-show}:
 * o atendimento vira REMOVED (a loja tirou, 18/09) e registra-se um {@link ClientPenaltyEvent} {@code NO_SHOW}
 * incrementando o {@code strikeCount} do cliente. Exige status LATE (senao 400). Convidados (sem
 * conta) sao removidos mas NAO acumulam strike.
 *
 * Cada atendimento dura 30 min e as refeicoes recorrentes sao removidas no setup -> ETA determinstico.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NoShowEscalationIntegrationTest {

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

        log.info("[NoShow] setup — estab={} prof={}", establishmentId, professionalId);
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

    /** Cria um convidado (sem conta) e devolve o id do attendance (via fila). */
    private String createGuest() throws Exception {
        AttendanceGuestRequest request = new AttendanceGuestRequest(
                UUID.fromString(establishmentId),
                UUID.fromString(professionalId),
                serviceItemIds,
                "Convidado Ausente");
        mvc.perform(post("/professional-queue/add-quest-attendance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
        // convidado = unico na fila de espera sem client
        return attendanceRepository
                .findWaitingQueueByProfessionalAndEstablishment(
                        UUID.fromString(professionalId), UUID.fromString(establishmentId))
                .stream()
                .filter(a -> a.getClient() == null)
                .findFirst().orElseThrow().getId().toString();
    }

    private void markLate(String id) throws Exception {
        mvc.perform(patch("/attendances/{id}/late", id)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk());
    }

    private AttendanceStatus statusOf(String id) {
        return attendanceRepository.findById(UUID.fromString(id)).orElseThrow().getStatus();
    }

    private boolean inWaiting(String id) {
        List<Attendance> waiting = attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(
                UUID.fromString(professionalId), UUID.fromString(establishmentId));
        return waiting.stream().anyMatch(a -> a.getId().toString().equals(id));
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
    @DisplayName("LATE -> no-show remove da fila (REMOVED) + registra strike NO_SHOW + expoe count")
    void noShow_onLate_removesAndStrikes() throws Exception {
        Q c1 = createWalkin();
        createWalkin(); // proximo, para o c1 ter atras de quem cair no /late

        markLate(c1.id());
        assertThat(statusOf(c1.id())).isEqualTo(AttendanceStatus.LATE);

        mvc.perform(patch("/attendances/{id}/no-show", c1.id())
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk());

        // 18/09: o no-show e a LOJA tirando o cliente da fila -> REMOVED.
        assertThat(statusOf(c1.id())).as("removido -> REMOVED").isEqualTo(AttendanceStatus.REMOVED);
        assertThat(inWaiting(c1.id())).as("saiu da fila de espera").isFalse();

        List<ClientPenaltyEvent> events = penaltyEventRepository
                .findByClientIdOrderByCreatedAtDesc(UUID.fromString(c1.clientId()));
        assertThat(events).as("1 evento NO_SHOW registrado").hasSize(1);
        assertThat(events.get(0).getType()).isEqualTo(PenaltyType.NO_SHOW);
        assertThat(events.get(0).getAmount()).as("D4 (18/09): nao comparecer custa R$ 5,00").isEqualByComparingTo("5.00");
        assertThat(events.get(0).getAttendanceId().toString()).isEqualTo(c1.id());

        assertThat(strikeCountOf(c1)).as("strikeCount exposto = 1").isEqualTo(1);
    }

    @Test
    @Order(11)
    @DisplayName("no-show em atendimento SCHEDULED (nao-LATE) -> 400, sem strike")
    void noShow_notLate_400() throws Exception {
        Q q = createWalkin(); // SCHEDULED

        mvc.perform(patch("/attendances/{id}/no-show", q.id())
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isBadRequest());

        assertThat(statusOf(q.id())).as("permanece SCHEDULED").isEqualTo(AttendanceStatus.SCHEDULED);
        assertThat(penaltyEventRepository.countByClientId(UUID.fromString(q.clientId())))
                .as("sem strike").isZero();
    }

    @Test
    @Order(12)
    @DisplayName("Convidado LATE -> no-show remove da fila mas NAO acumula strike (sem conta)")
    void noShow_guest_removesWithoutStrike() throws Exception {
        String guest = createGuest();

        markLate(guest);
        assertThat(statusOf(guest)).isEqualTo(AttendanceStatus.LATE);

        mvc.perform(patch("/attendances/{id}/no-show", guest)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk());

        assertThat(statusOf(guest)).as("convidado removido -> REMOVED").isEqualTo(AttendanceStatus.REMOVED);
        assertThat(inWaiting(guest)).as("saiu da fila").isFalse();
        assertThat(penaltyEventRepository.count()).as("convidado nao gera evento de penalidade").isZero();
    }

    @Test
    @Order(13)
    @DisplayName("no-show — 403 token CLIENT nao tem role PROFESSIONAL")
    void noShow_clientToken_403() throws Exception {
        Q q = createWalkin();

        mvc.perform(patch("/attendances/{id}/no-show", q.id())
                        .header("Authorization", q.token()))
                .andExpect(status().isForbidden());
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
