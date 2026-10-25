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
 * Integration tests — o "nao compareceu" (falta-vira-atraso, DEC-86): depois da espera, o profissional
 * marca {@code PATCH /attendances/{id}/no-show} e vale como um atraso (troca de lugar, soma no contador);
 * so no 3o vem a multa. Substitui a multa imediata da falta (DEC-83).
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
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
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

    /** O profissional tira o cliente da fila (limpa o cenario para o proximo teste). */
    private void cancel(Q q) throws Exception {
        mvc.perform(delete("/professional-queue/{id}/remove", q.id()).header("Authorization", ownerProfToken))
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
    @DisplayName("falta-vira-atraso: cedo demais -> 409 NO_SHOW_TOO_EARLY com available_at; o segundo da fila -> 409 NO_SHOW_NOT_FIRST")
    void noShow_tooEarlyAndNotFirst() throws Exception {
        Q first = createWalkin();
        Q second = createWalkin();

        mvc.perform(patch("/attendances/{id}/no-show", first.id()).header("Authorization", ownerProfToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_SHOW_TOO_EARLY"))
                .andExpect(jsonPath("$.available_at").isNotEmpty())
                .andExpect(jsonPath("$.seconds_left").isNumber());
        // regra-do-atraso-na-fila-vazia (DEC-101): o app so mostra o que o servidor diz que falta
        mvc.perform(get("/attendances/{id}/no-show-status", first.id()).header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_first").value(true))
                .andExpect(jsonPath("$.can_mark_no_show").value(false))
                .andExpect(jsonPath("$.reason").value("TOO_EARLY"))
                .andExpect(jsonPath("$.wait_minutes").value(10))
                .andExpect(jsonPath("$.seconds_left").value(org.hamcrest.Matchers.greaterThan(0)));
        mvc.perform(get("/attendances/{id}/no-show-status", second.id()).header("Authorization", ownerProfToken))
                .andExpect(jsonPath("$.is_first").value(false))
                .andExpect(jsonPath("$.reason").value("NOT_FIRST"))
                .andExpect(jsonPath("$.seconds_left").doesNotExist());
        mvc.perform(patch("/attendances/{id}/no-show", second.id()).header("Authorization", ownerProfToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_SHOW_NOT_FIRST"));

        assertThat(statusOf(first.id())).isEqualTo(AttendanceStatus.SCHEDULED);
        assertThat(penaltyEventRepository.countByClientId(UUID.fromString(first.clientId()))).isZero();
        cancel(first);
        cancel(second);
    }

    @Test
    @Order(11)
    @DisplayName("falta-vira-atraso (DEC-86): passada a espera, o 1o no-show vira ATRASADO, o proximo sobe e nao ha multa")
    void noShow_firstTimeIsLate() throws Exception {
        Q first = createWalkin();
        Q second = createWalkin();
        jdbc.update("UPDATE attendance SET promised_time = now() - interval '2 hours', "
                + "scheduled_time = now() - interval '2 hours' WHERE id = ?", UUID.fromString(first.id()));

        mvc.perform(get("/attendances/{id}/no-show-status", first.id()).header("Authorization", ownerProfToken))
                .andExpect(jsonPath("$.can_mark_no_show").value(true))
                .andExpect(jsonPath("$.seconds_left").value(0))
                .andExpect(jsonPath("$.reason").doesNotExist());
        mvc.perform(patch("/attendances/{id}/no-show", first.id()).header("Authorization", ownerProfToken))
                .andExpect(status().isOk());

        assertThat(statusOf(first.id())).isEqualTo(AttendanceStatus.LATE);
        assertThat(inWaiting(first.id())).isTrue();
        assertThat(penaltyEventRepository.countByClientId(UUID.fromString(first.clientId()))).isZero();
        List<Attendance> waiting = attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(
                UUID.fromString(professionalId), UUID.fromString(establishmentId));
        assertThat(waiting.get(0).getId().toString()).isEqualTo(second.id());
        cancel(first);
        cancel(second);
    }

    @Test
    @Order(12)
    @DisplayName("falta-vira-atraso (DEC-86): no 3o no-show do mesmo cliente nasce a multa de atraso e ele sai da fila (REMOVED)")
    void noShow_thirdTimeCostsPenalty() throws Exception {
        Q only = createWalkin();
        jdbc.update("UPDATE attendance SET promised_time = now() - interval '2 hours', "
                + "scheduled_time = now() - interval '2 hours' WHERE id = ?", UUID.fromString(only.id()));

        for (int i = 0; i < 3; i++) {
            mvc.perform(patch("/attendances/{id}/no-show", only.id()).header("Authorization", ownerProfToken))
                    .andExpect(status().isOk());
        }

        var events = penaltyEventRepository.findByClientIdOrderByCreatedAtDesc(UUID.fromString(only.clientId()));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getType()).isEqualTo(com.ospaulos.vupth.domain.client.model.PenaltyType.LATE_ARRIVAL);
        assertThat(events.get(0).getAmount()).isEqualByComparingTo("5.00");
        assertThat(statusOf(only.id())).isEqualTo(AttendanceStatus.REMOVED);
        assertThat(inWaiting(only.id())).isFalse();
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
