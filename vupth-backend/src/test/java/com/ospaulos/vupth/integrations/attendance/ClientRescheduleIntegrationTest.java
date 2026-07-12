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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — item #43 (remanejar cliente com espera longa).
 *
 * Duas acoes client-facing sobre a PROPRIA vez (SCHEDULED), quando a espera fica longa:
 * <ul>
 *   <li>{@code PATCH /attendances/{id}/reschedule?targetTime=} (Opcao A) — vira INTENCAO de fila (#40):
 *       viabilidade -> 422 se infactivel; alvo no passado / status invalido -> 400; dono -> 403.</li>
 *   <li>{@code PATCH /attendances/{id}/move-to-end} (Opcao B) — desce para o fim da fila (#19 reposicao);
 *       no-op se ja for o ultimo; status invalido -> 400; dono -> 403.</li>
 * </ul>
 * Cada atendimento dura 30 min e as refeicoes recorrentes sao removidas no setup -> ETA determinstico.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClientRescheduleIntegrationTest {

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

        // ETA puro = tempo de servico (sem refeicoes recorrentes).
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));

        var s1 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(s1.id()));

        log.info("[Reschedule] setup — estab={} prof={}", establishmentId, professionalId);
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

    private void markLate(String id) throws Exception {
        mvc.perform(patch("/attendances/{id}/late", id)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk());
    }

    private Attendance load(String id) {
        return attendanceRepository.findById(UUID.fromString(id)).orElseThrow();
    }

    /** Ids da fila de espera (SCHEDULED+LATE) na ordem de atendimento (scheduledTime ASC). */
    private List<String> waitingIds() {
        return attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(
                        UUID.fromString(professionalId), UUID.fromString(establishmentId))
                .stream().map(a -> a.getId().toString()).toList();
    }

    @AfterEach
    void clearQueue() {
        attendanceRepository.deleteAll();
    }

    // ─────────────────────────────────────────────────────────────
    // Opcao A — reschedule por horario-alvo
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("reschedule SCHEDULED com alvo factivel -> 200, vira INTENCAO (isIntent + targetTime + slot)")
    void reschedule_feasible_becomesIntent() throws Exception {
        Q c = createWalkin();
        OffsetDateTime target = OffsetDateTime.now().plusHours(3);

        mvc.perform(patch("/attendances/{id}/reschedule", c.id())
                        .param("targetTime", target.toString())
                        .header("Authorization", c.token()))
                .andExpect(status().isOk());

        Attendance a = load(c.id());
        assertThat(a.isIntent()).as("virou intencao").isTrue();
        assertThat(a.getTargetTime()).as("alvo persistido").isNotNull();
        assertThat(a.getStatus()).as("continua SCHEDULED (intencao e flag)").isEqualTo(AttendanceStatus.SCHEDULED);
        // Compara contra o alvo PERSISTIDO (mesma precisao de micros do banco): comparar com o
        // `target` em memoria falharia por nanos truncados no store (slot >= alvo e o invariante real).
        assertThat(a.getScheduledTime()).as("slot >= alvo (cede a vez ate encostar no alvo)")
                .isAfterOrEqualTo(a.getTargetTime());
    }

    @Test
    @Order(11)
    @DisplayName("reschedule com alvo infactivel (fila ja preve depois do alvo) -> 422")
    void reschedule_infeasible_422() throws Exception {
        Q c = createWalkin();
        createWalkin(); // outro ahead -> ETA excluindo o proprio ~30min
        createWalkin(); // mais um -> ~60min

        // alvo daqui a 2min, mas a fila (sem o proprio) ja preve ~60min -> infactivel
        OffsetDateTime target = OffsetDateTime.now().plusMinutes(2);

        mvc.perform(patch("/attendances/{id}/reschedule", c.id())
                        .param("targetTime", target.toString())
                        .header("Authorization", c.token()))
                .andExpect(status().isUnprocessableEntity());

        assertThat(load(c.id()).isIntent()).as("nao virou intencao").isFalse();
    }

    @Test
    @Order(12)
    @DisplayName("reschedule com alvo no passado -> 400")
    void reschedule_pastTarget_400() throws Exception {
        Q c = createWalkin();
        OffsetDateTime target = OffsetDateTime.now().minusMinutes(5);

        mvc.perform(patch("/attendances/{id}/reschedule", c.id())
                        .param("targetTime", target.toString())
                        .header("Authorization", c.token()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(13)
    @DisplayName("reschedule de atendimento LATE (nao-SCHEDULED) -> 400")
    void reschedule_notScheduled_400() throws Exception {
        Q c = createWalkin();
        markLate(c.id());
        OffsetDateTime target = OffsetDateTime.now().plusHours(3);

        mvc.perform(patch("/attendances/{id}/reschedule", c.id())
                        .param("targetTime", target.toString())
                        .header("Authorization", c.token()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(14)
    @DisplayName("reschedule da vez de OUTRO cliente -> 403")
    void reschedule_notOwner_403() throws Exception {
        Q c1 = createWalkin();
        Q c2 = createWalkin();
        OffsetDateTime target = OffsetDateTime.now().plusHours(3);

        mvc.perform(patch("/attendances/{id}/reschedule", c1.id())
                        .param("targetTime", target.toString())
                        .header("Authorization", c2.token()))
                .andExpect(status().isForbidden());
    }

    // ─────────────────────────────────────────────────────────────
    // Opcao B — move-to-end
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("move-to-end -> 200, o primeiro passa a ser o ultimo da fila")
    void moveToEnd_goesLast() throws Exception {
        Q c1 = createWalkin();
        createWalkin();
        createWalkin();

        assertThat(waitingIds().get(0)).as("c1 comeca em 1o").isEqualTo(c1.id());

        mvc.perform(patch("/attendances/{id}/move-to-end", c1.id())
                        .header("Authorization", c1.token()))
                .andExpect(status().isOk());

        List<String> after = waitingIds();
        assertThat(after).hasSize(3);
        assertThat(after.get(after.size() - 1)).as("c1 agora e o ultimo").isEqualTo(c1.id());
        assertThat(load(c1.id()).getStatus()).as("segue na fila (SCHEDULED)").isEqualTo(AttendanceStatus.SCHEDULED);
    }

    @Test
    @Order(21)
    @DisplayName("move-to-end quando ja e o ultimo -> 200 no-op, segue SCHEDULED no fim")
    void moveToEnd_alreadyLast_noop() throws Exception {
        createWalkin();
        Q last = createWalkin();

        mvc.perform(patch("/attendances/{id}/move-to-end", last.id())
                        .header("Authorization", last.token()))
                .andExpect(status().isOk());

        List<String> ids = waitingIds();
        assertThat(ids.get(ids.size() - 1)).as("continua ultimo").isEqualTo(last.id());
        assertThat(load(last.id()).getStatus()).isEqualTo(AttendanceStatus.SCHEDULED);
    }

    @Test
    @Order(22)
    @DisplayName("move-to-end de atendimento LATE (nao-SCHEDULED) -> 400")
    void moveToEnd_notScheduled_400() throws Exception {
        Q c = createWalkin();
        createWalkin();
        markLate(c.id());

        mvc.perform(patch("/attendances/{id}/move-to-end", c.id())
                        .header("Authorization", c.token()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(23)
    @DisplayName("move-to-end da vez de OUTRO cliente -> 403")
    void moveToEnd_notOwner_403() throws Exception {
        Q c1 = createWalkin();
        Q c2 = createWalkin();

        mvc.perform(patch("/attendances/{id}/move-to-end", c1.id())
                        .header("Authorization", c2.token()))
                .andExpect(status().isForbidden());
    }

    @AfterAll
    void tearDown() {
        attendanceRepository.deleteAll();
        serviceItemRepository.deleteAll();
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}
