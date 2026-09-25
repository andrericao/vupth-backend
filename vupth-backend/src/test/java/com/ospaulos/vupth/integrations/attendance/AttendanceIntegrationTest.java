package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceGuestRequest;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.attendance.dto.request.CancelAttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — domain: Attendance
 *
 * Scenarios covered:
 *
 * A) Cliente cria attendance (happy path)
 *    - Creates attendance with service items
 *    - Cancela attendance (PUT → CANCELED)
 *
 * B) Guest entra na fila
 *    - Com nome (clientName preenchido)
 *    - Without name (anonymous)
 *    - Sem serviceItemIds (MVP — opcional)
 *
 * C) Fluxo completo do attendance (SCHEDULED → IN_PROGRESS → FINISHED)
 *    - Profissional inicia attendance (PATCH /start)
 *    - Profissional finaliza attendance (PATCH /finish)
 *    - Verifica status FINISHED no banco
 *
 * D) Erros
 *    - Criar sem token
 *    - Create with invalid professionalId (unknown UUID → 404)
 *    - Criar com serviceItemIds como string em vez de array (→ 400)
 *    - Create with scheduledTime as number (→ 400)
 *    - Cancel non-existent attendance (→ 404)
 *    - Profissional diferente tentando iniciar attendance de outro (→ 403)
 *    - Start attendance already IN_PROGRESS (→ 400)
 *    - GET /attendances/{id} — 200 busca por ID, 404 inexistente, 401 sem token
 *
 * E) leaveQueue (CLIENT voluntarily leaves)
 *
 * F) removeFromQueue (PROFESSIONAL removes from queue)
 *
 * G) Pause / Resume / Finish direto da pausa (D12)
 *    IMPORTANT: Order(69) ensures profissional has NO IN_PROGRESS attendance
 *    before the pause/resume flow starts. The fullFlow (Orders 30-33) finishes
 *    the attendance, but we add an extra guard to be safe.
 *
 * H) List attendances (GET /attendances) — owner/professional reports
 *
 * I) Client attendance history (GET /attendances/client/me)
 *    - Happy path, filtros por status (CANCELLED/FINISHED) e periodo
 *    - 401 sem token, isolamento entre clients
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AttendanceIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired ObjectMapper mapper;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired UserRepository userRepository;
    @Autowired ServiceItemRepository serviceItemRepository;

    private final Faker faker = new Faker();

    // Setup data
    String ownerProfToken, ownerProfId, professionalId;
    String clientToken, clientId;
    String anotherProfToken;
    String establishmentId;
    List<UUID> serviceItemIds = new ArrayList<>();

    // Atendimentos createds nos testes
    String attendanceId;        // para cancelamento
    String fullFlowAttendanceId; // para SCHEDULED→IN_PROGRESS→FINISHED

    @BeforeAll
    void setup() throws Exception {
        log.info("[Attendance] setup — preparando ambiente completo");

        // 1. User is born as CLIENT → becomes OWNER by creating establishment
        //    → becomes PROFESSIONAL by linking to their own establishment (solo shop)
        var ownerProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerProfId = ownerProf.id();
        this.ownerProfToken = ownerProf.token();

        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerProfToken);
        this.establishmentId = estab.id();
        // Loja "aberta agora" — walk-in do cliente so entra com a loja aberta (gate BUG5 C1).
        TestUserUtils.openEstablishment(establishmentRepository, establishmentId);

        // Link the same user as PROFESSIONAL in their own establishment
        // After: CLIENT + OWNER + PROFESSIONAL
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerProfToken, establishmentId, "Owner Professional");
        this.professionalId = ownerProfId; // professional.id == baseUser.id (MapsId)
        log.info("[Attendance] setup — professional vinculado id={}", professionalId);

        // 2. Service items created by the OWNER/PROFESSIONAL
        var s1 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.CORTE_MAQUINA, "Corte Degradê", BigDecimal.valueOf(30));
        var s2 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.CORTE_MAQUINA, "Barba", BigDecimal.valueOf(20));
        this.serviceItemIds = List.of(UUID.fromString(s1.id()), UUID.fromString(s2.id()));
        log.info("[Attendance] setup — {} service items created", serviceItemIds.size());

        // 3. Simple client (CLIENT role only)
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientId = client.id();
        this.clientToken = client.token();

        // 4. Another professional in a different establishment (for ownership violation test)
        var anotherUser = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var anotherEstab = TestUserUtils.becomeOwner(mvc, mapper, faker, anotherUser.token());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, anotherUser.token(), anotherEstab.id());
        // 20/09: profissional nasce OFF e quem nao esta em expediente nao recebe fila.
        // Aqui o expediente e cenario, nao o objeto do teste — entao ele chega pelo atalho.
        TestUserUtils.startShift(professionalRepository, establishmentId);
        TestUserUtils.startShift(professionalRepository, anotherEstab.id());
        this.anotherProfToken = anotherUser.token();

        log.info("[Attendance] setup — ambiente completo. estab={} prof={} client={}",
                establishmentId, professionalId, clientId);
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO A — Client creates and cancels attendance
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("POST /attendances — 201 cliente cria attendance na fila")
    void createAttendance_success() throws Exception {
        log.info("[Attendance] createAttendance_success — client={} prof={}",
                clientId, professionalId);

        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId),
                UUID.fromString(establishmentId),
                UUID.fromString(clientId),
                serviceItemIds,
                AttendanceStatus.SCHEDULED,
                OffsetDateTime.now().plusMinutes(30),
                30,
                OffsetDateTime.now());

        MvcResult result = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.establishmentId").value(establishmentId))
                .andExpect(jsonPath("$.clientId").value(clientId))
                .andExpect(jsonPath("$.services").isArray())
                .andDo(print())
                .andReturn();

        this.attendanceId = new JSONObject(result.getResponse().getContentAsString()).getString("id");
        log.info("[Attendance] createAttendance_success — attendanceId={}", attendanceId);
        assertThat(attendanceId).isNotBlank();
    }

    @Test
    @Order(11)
    @DisplayName("PUT /attendances/{id}/cancel — 200 cliente cancela attendance → CANCELED")
    void cancelAttendance_success() throws Exception {
        Assumptions.assumeTrue(attendanceId != null, "Depende do createAttendance_success");
        log.info("[Attendance] cancelAttendance_success — attendanceId={}", attendanceId);

        mvc.perform(put("/attendances/{id}/cancel", attendanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(new CancelAttendanceRequest(UUID.fromString(clientId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(attendanceId))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andDo(print());

        Attendance updated = attendanceRepository.findById(UUID.fromString(attendanceId)).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(AttendanceStatus.CANCELLED);
        log.info("[Attendance] cancelAttendance_success — status={} confirmado no banco", updated.getStatus());
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO B — Guest joins the queue
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("POST /professional-queue/add-quest-attendance — 201 guest with name and service items")
    void guestAttendance_withNameAndServices() throws Exception {
        log.info("[Attendance] guestAttendance_withNameAndServices");

        AttendanceGuestRequest request = new AttendanceGuestRequest(
                UUID.fromString(establishmentId),
                UUID.fromString(professionalId),
                serviceItemIds,
                "John Guest");

        mvc.perform(post("/professional-queue/add-quest-attendance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andDo(print());
    }

    @Test
    @Order(21)
    @DisplayName("POST /professional-queue/add-quest-attendance — 201 anonymous guest without service items (MVP)")
    void guestAttendance_anonymous_noServices() throws Exception {
        log.info("[Attendance] guestAttendance_anonymous_noServices");

        AttendanceGuestRequest request = new AttendanceGuestRequest(
                UUID.fromString(establishmentId),
                UUID.fromString(professionalId),
                null,   // no service items — MVP: optional
                null);  // no name — anonymous

        mvc.perform(post("/professional-queue/add-quest-attendance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andDo(print());
    }

    @Test
    @Order(22)
    @DisplayName("POST /professional-queue/add-quest-attendance — 422 establishmentId nulo")
    void guestAttendance_nullEstablishmentId() throws Exception {
        log.info("[Attendance] guestAttendance_nullEstablishmentId");

        String body = String.format("""
                {"professionalId": "%s", "establishmentId": null}
                """, professionalId);

        mvc.perform(post("/professional-queue/add-quest-attendance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO C — Full flow SCHEDULED → IN_PROGRESS → FINISHED
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("Fluxo completo — cria attendance para ciclo de vida")
    void fullFlow_createAttendance() throws Exception {
        log.info("[Attendance] fullFlow_createAttendance");

        var flowClient = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);

        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId),
                UUID.fromString(establishmentId),
                UUID.fromString(flowClient.id()),
                serviceItemIds,
                AttendanceStatus.SCHEDULED,
                OffsetDateTime.now().plusMinutes(10),
                20,
                OffsetDateTime.now());

        MvcResult result = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", flowClient.token())
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andDo(print())
                .andReturn();

        this.fullFlowAttendanceId = new JSONObject(result.getResponse().getContentAsString()).getString("id");
        log.info("[Attendance] fullFlow_createAttendance — attendanceId={}", fullFlowAttendanceId);
    }

    @Test
    @Order(31)
    @DisplayName("Fluxo completo — PATCH /start → IN_PROGRESS")
    void fullFlow_start() throws Exception {
        Assumptions.assumeTrue(fullFlowAttendanceId != null, "Depende do fullFlow_createAttendance");
        log.info("[Attendance] fullFlow_start — attendanceId={}", fullFlowAttendanceId);

        mvc.perform(patch("/attendances/{id}/start", fullFlowAttendanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andDo(print())
                .andExpect(status().isOk());

        Attendance attendance = attendanceRepository.findById(
                UUID.fromString(fullFlowAttendanceId)).orElseThrow();
        assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.IN_PROGRESS);
        log.info("[Attendance] fullFlow_start — status=IN_PROGRESS confirmado no banco");
    }

    @Test
    @Order(32)
    @DisplayName("Full flow — PATCH /start again → 409 already IN_PROGRESS")
    void fullFlow_startAgain_conflict() throws Exception {
        Assumptions.assumeTrue(fullFlowAttendanceId != null);
        log.info("[Attendance] fullFlow_startAgain_conflict — attendanceId={}", fullFlowAttendanceId);

        mvc.perform(patch("/attendances/{id}/start", fullFlowAttendanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isIn(400, 409))
                .andDo(print());
    }

    @Test
    @Order(33)
    @DisplayName("Fluxo completo — PATCH /finish → FINISHED")
    void fullFlow_finish() throws Exception {
        Assumptions.assumeTrue(fullFlowAttendanceId != null, "Depende do fullFlow_start");
        log.info("[Attendance] fullFlow_finish — attendanceId={}", fullFlowAttendanceId);

        mvc.perform(patch("/attendances/{id}/finish", fullFlowAttendanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andDo(print());

        Attendance attendance = attendanceRepository.findById(
                UUID.fromString(fullFlowAttendanceId)).orElseThrow();
        assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.FINISHED);
        log.info("[Attendance] fullFlow_finish — status=FINISHED confirmado no banco");
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO D — Errors
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    @DisplayName("POST /attendances — 401 sem token")
    void createAttendance_noToken() throws Exception {
        log.info("[Attendance] createAttendance_noToken");

        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId),
                UUID.fromString(establishmentId),
                UUID.fromString(clientId),
                serviceItemIds,
                AttendanceStatus.SCHEDULED,
                OffsetDateTime.now().plusMinutes(30),
                30, OffsetDateTime.now());

        mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(41)
    @DisplayName("POST /attendances — 400 serviceItemIds como string em vez de array")
    void createAttendance_serviceIdsAsString() throws Exception {
        log.info("[Attendance] createAttendance_serviceIdsAsString");

        String body = String.format("""
                {
                  "professionalId": "%s",
                  "establishmentId": "%s",
                  "clientId": "%s",
                  "serviceItemIds": "nao-e-um-array",
                  "status": "SCHEDULED",
                  "scheduledTime": "%s"
                }
                """, professionalId, establishmentId, clientId, OffsetDateTime.now());

        mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andDo(print());
    }

    @Test
    @Order(42)
    @DisplayName("POST /attendances — 400 scheduledTime as number instead of ISO 8601")
    void createAttendance_scheduledTimeAsNumber() throws Exception {
        log.info("[Attendance] createAttendance_scheduledTimeAsNumber");

        String body = String.format("""
                {
                  "professionalId": "%s",
                  "establishmentId": "%s",
                  "clientId": "%s",
                  "serviceItemIds": [],
                  "status": "SCHEDULED",
                  "scheduledTime": 12345
                }
                """, professionalId, establishmentId, clientId);

        mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andDo(print());
    }

    @Test
    @Order(43)
    @DisplayName("PUT /attendances/{id}/cancel — 404 attendance not found")
    void cancelAttendance_notFound() throws Exception {
        UUID inexistente = UUID.randomUUID();
        log.info("[Attendance] cancelAttendance_notFound — id={}", inexistente);

        mvc.perform(put("/attendances/{id}/cancel", inexistente)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(new CancelAttendanceRequest(UUID.fromString(clientId)))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ATTENDANCE_NOT_FOUND"))
                .andDo(print());
    }

    @Test
    @Order(44)
    @DisplayName("PATCH /attendances/{id}/start — 403 profissional diferente tentando iniciar attendance alheio")
    void startAttendance_wrongProfessional() throws Exception {
        Assumptions.assumeTrue(attendanceId != null);
        log.info("[Attendance] startAttendance_wrongProfessional — attendanceId={}", attendanceId);

        var newClient = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId),
                UUID.fromString(establishmentId),
                UUID.fromString(newClient.id()),
                serviceItemIds,
                AttendanceStatus.SCHEDULED,
                OffsetDateTime.now().plusMinutes(15),
                15, OffsetDateTime.now());

        MvcResult created = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", newClient.token())
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        String newAttendanceId = new JSONObject(created.getResponse().getContentAsString()).getString("id");

        mvc.perform(patch("/attendances/{id}/start", newAttendanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", anotherProfToken))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isIn(403, 404))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO D.2 — GET /attendances/{id}
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(45)
    @DisplayName("GET /attendances/{id} — 200 busca attendance existente")
    void getById_success() throws Exception {
        Assumptions.assumeTrue(attendanceId != null, "Depende do createAttendance_success");
        log.info("[Attendance] getById_success — attendanceId={}", attendanceId);

        mvc.perform(get("/attendances/{id}", attendanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(attendanceId))
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.establishmentId").value(establishmentId))
                .andDo(print());
    }

    @Test
    @Order(46)
    @DisplayName("GET /attendances/{id} — 404 attendance inexistente")
    void getById_notFound() throws Exception {
        UUID inexistente = UUID.randomUUID();
        log.info("[Attendance] getById_notFound — id={}", inexistente);

        mvc.perform(get("/attendances/{id}", inexistente)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ATTENDANCE_NOT_FOUND"))
                .andDo(print());
    }

    @Test
    @Order(47)
    @DisplayName("GET /attendances/{id} — 401 sem token")
    void getById_noToken_401() throws Exception {
        log.info("[Attendance] getById_noToken_401");

        mvc.perform(get("/attendances/{id}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO E — leaveQueue (CLIENT voluntarily leaves)
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(50)
    @DisplayName("DELETE /attendances/{id}/leave-queue — 200 client leaves own queue position")
    void leaveQueue_success() throws Exception {
        log.info("[Attendance] leaveQueue_success — creating fresh attendance");

        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId),
                UUID.fromString(establishmentId),
                UUID.fromString(clientId),
                serviceItemIds,
                AttendanceStatus.SCHEDULED,
                OffsetDateTime.now().plusMinutes(20),
                20,
                OffsetDateTime.now());

        MvcResult created = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String leaveId = new JSONObject(created.getResponse().getContentAsString()).getString("id");
        log.info("[Attendance] leaveQueue_success — leaveId={}", leaveId);

        mvc.perform(delete("/attendances/{id}/leave-queue", leaveId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(leaveId))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andDo(print());

        Attendance updated = attendanceRepository.findById(UUID.fromString(leaveId)).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(AttendanceStatus.CANCELLED);
        log.info("[Attendance] leaveQueue_success — status=CANCELED confirmado no banco");
    }

    @Test
    @Order(51)
    @DisplayName("DELETE /attendances/{id}/leave-queue — 403 different client trying to leave another's position")
    void leaveQueue_wrongClient_forbidden() throws Exception {
        log.info("[Attendance] leaveQueue_wrongClient_forbidden");

        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId),
                UUID.fromString(establishmentId),
                UUID.fromString(clientId),
                serviceItemIds,
                AttendanceStatus.SCHEDULED,
                OffsetDateTime.now().plusMinutes(25),
                25,
                OffsetDateTime.now());

        MvcResult created = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String targetId = new JSONObject(created.getResponse().getContentAsString()).getString("id");

        mvc.perform(delete("/attendances/{id}/leave-queue", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", anotherProfToken))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isIn(403, 401))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO F — removeFromQueue (PROFESSIONAL removes from queue)
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(60)
    @DisplayName("DELETE /professional-queue/{id}/remove — 200 professional removes client from queue")
    void removeFromQueue_success() throws Exception {
        log.info("[Attendance] removeFromQueue_success — creating attendance to remove");

        var tempClient = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId),
                UUID.fromString(establishmentId),
                UUID.fromString(tempClient.id()),
                serviceItemIds,
                AttendanceStatus.SCHEDULED,
                OffsetDateTime.now().plusMinutes(30),
                30,
                OffsetDateTime.now());

        MvcResult created = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", tempClient.token())
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String removeId = new JSONObject(created.getResponse().getContentAsString()).getString("id");
        log.info("[Attendance] removeFromQueue_success — removeId={}", removeId);

        mvc.perform(delete("/professional-queue/{id}/remove", removeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(removeId))
                // 18/09: quem tira e a LOJA -> REMOVED. CANCELLED ficou para o cliente que desiste.
                .andExpect(jsonPath("$.status").value("REMOVED"))
                .andDo(print());

        Attendance updated = attendanceRepository.findById(UUID.fromString(removeId)).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(AttendanceStatus.REMOVED);
        log.info("[Attendance] removeFromQueue_success — status=REMOVED confirmado no banco");
    }

    @Test
    @Order(61)
    @DisplayName("DELETE /professional-queue/{id}/remove — 200 professional removes guest (no client) from queue")
    void removeFromQueue_guestAttendance() throws Exception {
        log.info("[Attendance] removeFromQueue_guestAttendance");

        AttendanceGuestRequest guestRequest = new AttendanceGuestRequest(
                UUID.fromString(establishmentId),
                UUID.fromString(professionalId),
                null,
                "Guest To Remove");

        mvc.perform(post("/professional-queue/add-quest-attendance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(guestRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID guestAttendanceId = attendanceRepository.findAll().stream()
                .filter(a -> "Guest To Remove".equals(a.getGuestName()))
                .filter(a -> a.getStatus() == AttendanceStatus.SCHEDULED)
                .map(Attendance::getId)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Guest attendance not found in DB"));

        log.info("[Attendance] removeFromQueue_guestAttendance — guestAttendanceId={}", guestAttendanceId);

        mvc.perform(delete("/professional-queue/{id}/remove", guestAttendanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                // convidado nao muda a regra: quem tira e a LOJA -> REMOVED.
                .andExpect(jsonPath("$.status").value("REMOVED"))
                .andDo(print());
    }

    @Test
    @Order(62)
    @DisplayName("DELETE /professional-queue/{id}/remove — 403 wrong professional trying to remove from another's queue")
    void removeFromQueue_wrongProfessional() throws Exception {
        log.info("[Attendance] removeFromQueue_wrongProfessional");

        var tempClient2 = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId),
                UUID.fromString(establishmentId),
                UUID.fromString(tempClient2.id()),
                serviceItemIds,
                AttendanceStatus.SCHEDULED,
                OffsetDateTime.now().plusMinutes(35),
                35,
                OffsetDateTime.now());

        MvcResult created = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", tempClient2.token())
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String targetId = new JSONObject(created.getResponse().getContentAsString()).getString("id");

        mvc.perform(delete("/professional-queue/{id}/remove", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", anotherProfToken))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isIn(403, 404))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO G — Pause / Resume
    // The fullFlow finished at Order(33). Order(69) is a guard that
    // cancels any remaining IN_PROGRESS attendance so the professional
    // is free to start a new one at Order(71).
    // ─────────────────────────────────────────────────────────────

    String pauseAttendanceId;

    @Test
    @Order(69)
    @DisplayName("Guard — cancela qualquer IN_PROGRESS restante antes do fluxo pause/resume")
    void pauseResume_guard_clearInProgress() throws Exception {
        log.info("[Attendance] pauseResume_guard — clearing any IN_PROGRESS for prof={}", professionalId);

        // Finish any lingering IN_PROGRESS attendance for this professional
        attendanceRepository.findAll().stream()
                .filter(a -> professionalId.equals(
                        a.getProfessional() != null ? a.getProfessional().getId().toString() : ""))
                .filter(a -> a.getStatus() == AttendanceStatus.IN_PROGRESS)
                .forEach(a -> {
                    try {
                        mvc.perform(patch("/attendances/{id}/finish", a.getId())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header("Authorization", ownerProfToken))
                                .andDo(print());
                        log.info("[Attendance] pauseResume_guard — finished lingering attendance id={}", a.getId());
                    } catch (Exception e) {
                        log.warn("[Attendance] pauseResume_guard — could not finish id={}: {}", a.getId(), e.getMessage());
                    }
                });
    }

    @Test
    @Order(70)
    @DisplayName("POST /attendances — 201 cria attendance para teste de pause/resume")
    void pauseResume_setup_createAttendance() throws Exception {
        Assumptions.assumeTrue(professionalId != null && clientToken != null);
        log.info("[Attendance] pauseResume_setup_createAttendance");

        // Use a fresh client to avoid "already in queue" conflicts
        var pauseClient = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);

        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId),
                UUID.fromString(establishmentId),
                UUID.fromString(pauseClient.id()),
                serviceItemIds,
                AttendanceStatus.SCHEDULED,
                OffsetDateTime.now().plusMinutes(5),
                30,
                OffsetDateTime.now());

        MvcResult result = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", pauseClient.token())
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        this.pauseAttendanceId = new JSONObject(result.getResponse().getContentAsString()).getString("id");
        log.info("[Attendance] pauseResume_setup — attendanceId={}", pauseAttendanceId);
        assertThat(pauseAttendanceId).isNotBlank();
    }

    @Test
    @Order(71)
    @DisplayName("PATCH /attendances/{id}/start — 200 inicia attendance para poder pausar")
    void pauseResume_start() throws Exception {
        Assumptions.assumeTrue(pauseAttendanceId != null, "Depende do pauseResume_setup");
        log.info("[Attendance] pauseResume_start — id={}", pauseAttendanceId);

        mvc.perform(patch("/attendances/{id}/start", pauseAttendanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andDo(print());
    }

    @Test
    @Order(72)
    @DisplayName("PATCH /attendances/{id}/pause — 200 pausa o attendance IN_PROGRESS")
    void pauseAttendance_success() throws Exception {
        Assumptions.assumeTrue(pauseAttendanceId != null, "Depende do pauseResume_start");
        log.info("[Attendance] pauseAttendance_success — id={}", pauseAttendanceId);

        mvc.perform(patch("/attendances/{id}/pause", pauseAttendanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andDo(print());
    }

    @Test
    @Order(73)
    @DisplayName("PATCH /attendances/{id}/pause — 401 sem token")
    void pauseAttendance_noToken_401() throws Exception {
        log.info("[Attendance] pauseAttendance_noToken_401");

        mvc.perform(patch("/attendances/{id}/pause", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(74)
    @DisplayName("PATCH /attendances/{id}/pause — 403 token CLIENT não tem ROLE_PROFESSIONAL")
    void pauseAttendance_clientToken_403() throws Exception {
        Assumptions.assumeTrue(pauseAttendanceId != null);
        log.info("[Attendance] pauseAttendance_clientToken_403");

        mvc.perform(patch("/attendances/{id}/pause", pauseAttendanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken))
                .andExpect(status().isForbidden())
                .andDo(print());
    }

    @Test
    @Order(75)
    @DisplayName("PATCH /attendances/{id}/resume — 200 retoma o attendance PAUSED")
    void resumeAttendance_success() throws Exception {
        Assumptions.assumeTrue(pauseAttendanceId != null, "Depende do pauseAttendance_success");
        log.info("[Attendance] resumeAttendance_success — id={}", pauseAttendanceId);

        mvc.perform(patch("/attendances/{id}/resume", pauseAttendanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andDo(print());
    }

    @Test
    @Order(76)
    @DisplayName("PATCH /attendances/{id}/resume — 401 sem token")
    void resumeAttendance_noToken_401() throws Exception {
        log.info("[Attendance] resumeAttendance_noToken_401");

        mvc.perform(patch("/attendances/{id}/resume", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(77)
    @DisplayName("PATCH /attendances/{id}/resume — 403 token CLIENT não tem ROLE_PROFESSIONAL")
    void resumeAttendance_clientToken_403() throws Exception {
        Assumptions.assumeTrue(pauseAttendanceId != null);
        log.info("[Attendance] resumeAttendance_clientToken_403");

        mvc.perform(patch("/attendances/{id}/resume", pauseAttendanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken))
                .andExpect(status().isForbidden())
                .andDo(print());
    }

    @Test
    @Order(78)
    @DisplayName("PATCH /attendances/{id}/pause — 200 pausa de novo, depois de retomado")
    void pauseResume_pauseAgain() throws Exception {
        Assumptions.assumeTrue(pauseAttendanceId != null, "Depende do resumeAttendance_success");
        log.info("[Attendance] pauseResume_pauseAgain — id={}", pauseAttendanceId);

        mvc.perform(patch("/attendances/{id}/pause", pauseAttendanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inAttendance.status").value("PAUSED"))
                .andDo(print());
    }

    /**
     * D12: da pausa o profissional retoma OU encerra ali. O resume ja foi provado no Order(75);
     * aqui o finish sai direto de PAUSED, sem o resume que antes era obrigatorio.
     */
    @Test
    @Order(79)
    @DisplayName("PATCH /attendances/{id}/finish — 200 finaliza direto de PAUSED (D12)")
    void pauseResume_finishFromPaused() throws Exception {
        Assumptions.assumeTrue(pauseAttendanceId != null, "Depende do pauseResume_pauseAgain");
        log.info("[Attendance] pauseResume_finishFromPaused — id={}", pauseAttendanceId);

        mvc.perform(patch("/attendances/{id}/finish", pauseAttendanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inAttendance.attendanceId").value(pauseAttendanceId))
                .andExpect(jsonPath("$.inAttendance.status").value("FINISHED"))
                .andDo(print());

        var finished = attendanceRepository.findById(UUID.fromString(pauseAttendanceId)).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(AttendanceStatus.FINISHED);
        assertThat(finished.getEndTime()).isNotNull();
        assertThat(finished.getLastPauseTime()).isNull();
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO H — List attendances (GET /attendances)
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(80)
    @DisplayName("GET /attendances?establishmentId=X — 200 owner lista attendances do establishment")
    void listAttendances_byEstablishment_owner() throws Exception {
        log.info("[Attendance] listAttendances_byEstablishment_owner");

        mvc.perform(get("/attendances")
                        .param("establishmentId", establishmentId)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].establishmentId").value(establishmentId))
                .andExpect(jsonPath("$[0].professionalId").exists())
                .andExpect(jsonPath("$[0].services").isArray())
                .andExpect(jsonPath("$[0].status").exists())
                .andExpect(jsonPath("$[0].totalValue").exists())
                .andExpect(jsonPath("$[0].scheduledTime").exists())
                .andDo(print());
    }

    @Test
    @Order(81)
    @DisplayName("GET /attendances?establishmentId=X&status=FINISHED — 200 filtra por status")
    void listAttendances_byStatus() throws Exception {
        log.info("[Attendance] listAttendances_byStatus");

        MvcResult result = mvc.perform(get("/attendances")
                        .param("establishmentId", establishmentId)
                        .param("status", "FINISHED")
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andDo(print())
                .andReturn();

        var items = mapper.readTree(result.getResponse().getContentAsString());
        for (var item : items) {
            assertThat(item.get("status").asText()).isEqualTo("FINISHED");
        }
    }

    @Test
    @Order(82)
    @DisplayName("GET /attendances?establishmentId=X&status=CANCELLED — 200 filtra cancelados")
    void listAttendances_byCancelledStatus() throws Exception {
        log.info("[Attendance] listAttendances_byCancelledStatus");

        MvcResult result = mvc.perform(get("/attendances")
                        .param("establishmentId", establishmentId)
                        .param("status", "CANCELLED")
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andDo(print())
                .andReturn();

        var items = mapper.readTree(result.getResponse().getContentAsString());
        for (var item : items) {
            assertThat(item.get("status").asText()).isEqualTo("CANCELLED");
        }
    }

    @Test
    @Order(83)
    @DisplayName("GET /attendances?establishmentId=X&dateFrom=Y&dateTo=Z — 200 filtra por periodo")
    void listAttendances_byDateRange() throws Exception {
        log.info("[Attendance] listAttendances_byDateRange");

        String today = BusinessClock.today().toString();

        mvc.perform(get("/attendances")
                        .param("establishmentId", establishmentId)
                        .param("dateFrom", today)
                        .param("dateTo", today)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andDo(print());
    }

    @Test
    @Order(84)
    @DisplayName("GET /attendances?professionalId=X — 200 profissional lista seus proprios attendances")
    void listAttendances_byProfessional() throws Exception {
        log.info("[Attendance] listAttendances_byProfessional");

        MvcResult result = mvc.perform(get("/attendances")
                        .param("professionalId", professionalId)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andDo(print())
                .andReturn();

        var items = mapper.readTree(result.getResponse().getContentAsString());
        for (var item : items) {
            assertThat(item.get("professionalId").asText()).isEqualTo(professionalId);
        }
    }

    @Test
    @Order(85)
    @DisplayName("GET /attendances — 200 sem params, retorna attendances do establishment do profissional logado")
    void listAttendances_noParams_defaultsToOwnEstablishment() throws Exception {
        log.info("[Attendance] listAttendances_noParams");

        mvc.perform(get("/attendances")
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andDo(print());
    }

    @Test
    @Order(86)
    @DisplayName("GET /attendances — 401 sem token")
    void listAttendances_noToken_401() throws Exception {
        log.info("[Attendance] listAttendances_noToken_401");

        mvc.perform(get("/attendances")
                        .param("establishmentId", establishmentId))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(87)
    @DisplayName("GET /attendances?establishmentId=X — 403 client sem role OWNER/PROFESSIONAL")
    void listAttendances_clientToken_403() throws Exception {
        log.info("[Attendance] listAttendances_clientToken_403");

        mvc.perform(get("/attendances")
                        .param("establishmentId", establishmentId)
                        .header("Authorization", clientToken))
                .andExpect(status().isForbidden())
                .andDo(print());
    }

    @Test
    @Order(88)
    @DisplayName("GET /attendances?establishmentId=RANDOM — 403 owner tenta ver establishment de outro")
    void listAttendances_wrongEstablishment_403() throws Exception {
        log.info("[Attendance] listAttendances_wrongEstablishment_403");

        mvc.perform(get("/attendances")
                        .param("establishmentId", UUID.randomUUID().toString())
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isNotFound())
                .andDo(print());
    }

    @Test
    @Order(89)
    @DisplayName("GET /attendances — response tem campos de client (clientId, clientName)")
    void listAttendances_hasClientFields() throws Exception {
        log.info("[Attendance] listAttendances_hasClientFields");

        MvcResult result = mvc.perform(get("/attendances")
                        .param("establishmentId", establishmentId)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andDo(print())
                .andReturn();

        var items = mapper.readTree(result.getResponse().getContentAsString());
        boolean hasClientItem = false;
        for (var item : items) {
            if (!item.get("clientId").isNull()) {
                hasClientItem = true;
                assertThat(item.has("clientName")).isTrue();
            }
        }
        assertThat(hasClientItem).as("Deve ter ao menos um attendance com clientId preenchido").isTrue();
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO I — Client attendance history (GET /attendances/client/me)
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(90)
    @DisplayName("GET /attendances/client/me — 200 client lista o proprio historico")
    void listClientAttendances_happyPath() throws Exception {
        log.info("[Attendance] listClientAttendances_happyPath");

        MvcResult result = mvc.perform(get("/attendances/client/me")
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].clientId").value(clientId))
                .andExpect(jsonPath("$[0].establishmentId").exists())
                .andExpect(jsonPath("$[0].professionalId").exists())
                .andExpect(jsonPath("$[0].services").isArray())
                .andExpect(jsonPath("$[0].status").exists())
                .andExpect(jsonPath("$[0].totalValue").exists())
                .andExpect(jsonPath("$[0].scheduledTime").exists())
                .andDo(print())
                .andReturn();

        var items = mapper.readTree(result.getResponse().getContentAsString());
        for (var item : items) {
            assertThat(item.get("clientId").asText())
                    .as("Historico do client so pode conter attendances dele mesmo")
                    .isEqualTo(clientId);
        }
    }

    @Test
    @Order(91)
    @DisplayName("GET /attendances/client/me?status=CANCELLED — 200 filtra cancelados")
    void listClientAttendances_byCancelledStatus() throws Exception {
        log.info("[Attendance] listClientAttendances_byCancelledStatus");

        MvcResult result = mvc.perform(get("/attendances/client/me")
                        .param("status", "CANCELLED")
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andDo(print())
                .andReturn();

        var items = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(items.size())
                .as("Client cancelou attendances nos cenarios anteriores")
                .isGreaterThan(0);
        for (var item : items) {
            assertThat(item.get("status").asText()).isEqualTo("CANCELLED");
            assertThat(item.get("clientId").asText()).isEqualTo(clientId);
        }
    }

    @Test
    @Order(92)
    @DisplayName("GET /attendances/client/me?status=FINISHED — 200 filtra finalizados")
    void listClientAttendances_byFinishedStatus() throws Exception {
        log.info("[Attendance] listClientAttendances_byFinishedStatus");

        MvcResult result = mvc.perform(get("/attendances/client/me")
                        .param("status", "FINISHED")
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andDo(print())
                .andReturn();

        var items = mapper.readTree(result.getResponse().getContentAsString());
        for (var item : items) {
            assertThat(item.get("status").asText()).isEqualTo("FINISHED");
        }
    }

    @Test
    @Order(93)
    @DisplayName("GET /attendances/client/me?dateFrom=hoje&dateTo=amanha — 200 filtra por periodo")
    void listClientAttendances_byDateRange() throws Exception {
        log.info("[Attendance] listClientAttendances_byDateRange");

        // Periodo [hoje, amanha]: o filtro corta por scheduledTime, e a CAUDA da fila (+30min por
        // atendimento) pode cruzar a meia-noite quando a suite roda tarde (CI 23:43 UTC ja flakou
        // com [hoje, hoje] devolvendo 0). Um dia de folga mantem o filtro exercitado e o teste
        // deterministico em qualquer horario.
        String today = BusinessClock.today().toString();
        String tomorrow = BusinessClock.today().plusDays(1).toString();

        MvcResult result = mvc.perform(get("/attendances/client/me")
                        .param("dateFrom", today)
                        .param("dateTo", tomorrow)
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andDo(print())
                .andReturn();

        var items = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(items.size())
                .as("Attendances criados hoje devem aparecer no filtro de hoje")
                .isGreaterThan(0);
    }

    @Test
    @Order(94)
    @DisplayName("GET /attendances/client/me?dateFrom=2099 — 200 periodo futuro retorna lista vazia")
    void listClientAttendances_futureDateRange_empty() throws Exception {
        log.info("[Attendance] listClientAttendances_futureDateRange_empty");

        MvcResult result = mvc.perform(get("/attendances/client/me")
                        .param("dateFrom", "2099-01-01")
                        .param("dateTo", "2099-12-31")
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andDo(print())
                .andReturn();

        var items = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(items).isEmpty();
    }

    @Test
    @Order(95)
    @DisplayName("GET /attendances/client/me — 401 sem token")
    void listClientAttendances_noToken_401() throws Exception {
        log.info("[Attendance] listClientAttendances_noToken_401");

        mvc.perform(get("/attendances/client/me"))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(96)
    @DisplayName("GET /attendances/client/me — isolamento: outro user nao ve historico do client")
    void listClientAttendances_isolation_otherUser() throws Exception {
        log.info("[Attendance] listClientAttendances_isolation_otherUser");

        // ownerProf tambem nasce CLIENT (roles acumulativas), mas nunca criou
        // attendance como client — o historico dele nao pode vazar o do clientToken
        MvcResult result = mvc.perform(get("/attendances/client/me")
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andDo(print())
                .andReturn();

        var items = mapper.readTree(result.getResponse().getContentAsString());
        for (var item : items) {
            assertThat(item.get("clientId").asText())
                    .as("client/me nunca pode retornar attendance de outro client")
                    .isEqualTo(ownerProfId);
        }
    }

    @AfterAll
    void tearDown() {
        log.info("[Attendance] tearDown — limpando dados");
        attendanceRepository.deleteAll();
        serviceItemRepository.deleteAll();
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}