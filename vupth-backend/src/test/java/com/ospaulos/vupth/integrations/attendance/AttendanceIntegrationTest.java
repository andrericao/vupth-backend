package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
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

        // Bug fix: era delete() — o correto is put()
        mvc.perform(put("/attendances/{id}/cancel", attendanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(new CancelAttendanceRequest(UUID.fromString(clientId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(attendanceId))
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andDo(print());

        Attendance updated = attendanceRepository.findById(UUID.fromString(attendanceId)).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(AttendanceStatus.CANCELED);
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

        // Precisa de um novo cliente para este attendance
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
                .andDo(print())   // print BEFORE assertion to capture 500 body
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

        // Cria novo attendance (o attendanceId foi CANCELED no teste anterior)
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

        // Outro profissional tenta iniciar
        mvc.perform(patch("/attendances/{id}/start", newAttendanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", anotherProfToken))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isIn(403, 404))
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

        // Create a new attendance for this test
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

        // Client leaves the queue voluntarily
        mvc.perform(delete("/attendances/{id}/leave-queue", leaveId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(leaveId))
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andDo(print());

        Attendance updated = attendanceRepository.findById(UUID.fromString(leaveId)).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(AttendanceStatus.CANCELED);
        log.info("[Attendance] leaveQueue_success — status=CANCELED confirmado no banco");
    }

    @Test
    @Order(51)
    @DisplayName("DELETE /attendances/{id}/leave-queue — 403 different client trying to leave another's position")
    void leaveQueue_wrongClient_forbidden() throws Exception {
        log.info("[Attendance] leaveQueue_wrongClient_forbidden");

        // Create attendance for clientToken
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

        // Another user (anotherProfToken is a valid authenticated user with CLIENT role) tries to leave
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

        // Create a new attendance
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

        // Professional removes the attendance
        mvc.perform(delete("/professional-queue/{id}/remove", removeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(removeId))
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andDo(print());

        Attendance updated = attendanceRepository.findById(UUID.fromString(removeId)).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(AttendanceStatus.CANCELED);
        log.info("[Attendance] removeFromQueue_success — status=CANCELED confirmado no banco");
    }

    @Test
    @Order(61)
    @DisplayName("DELETE /professional-queue/{id}/remove — 200 professional removes guest (no client) from queue")
    void removeFromQueue_guestAttendance() throws Exception {
        log.info("[Attendance] removeFromQueue_guestAttendance");

        // Add a guest to the queue first
        AttendanceGuestRequest guestRequest = new AttendanceGuestRequest(
                UUID.fromString(establishmentId),
                UUID.fromString(professionalId),
                null,
                "Guest To Remove");

        MvcResult created = mvc.perform(post("/professional-queue/add-quest-attendance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(guestRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        // Get the guest attendance ID from the queue response
        JSONObject queueResponse = new JSONObject(created.getResponse().getContentAsString());
        // The response is a QueueResponse — find the last attendance in the queue
        // Use attendanceRepository to find the guest by guestName
        UUID guestAttendanceId = attendanceRepository.findAll().stream()
                .filter(a -> "Guest To Remove".equals(a.getGuestName()))
                .filter(a -> a.getStatus() == AttendanceStatus.SCHEDULED)
                .map(Attendance::getId)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Guest attendance not found in DB"));

        log.info("[Attendance] removeFromQueue_guestAttendance — guestAttendanceId={}", guestAttendanceId);

        // Professional removes the guest
        mvc.perform(delete("/professional-queue/{id}/remove", guestAttendanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andDo(print());
    }

    @Test
    @Order(62)
    @DisplayName("DELETE /professional-queue/{id}/remove — 403 wrong professional trying to remove from another's queue")
    void removeFromQueue_wrongProfessional() throws Exception {
        log.info("[Attendance] removeFromQueue_wrongProfessional");

        // Create attendance in ownerProf's queue
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

        // anotherProfToken is from a DIFFERENT professional → should be 403
        mvc.perform(delete("/professional-queue/{id}/remove", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", anotherProfToken))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isIn(403, 404))
                .andDo(print());
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
