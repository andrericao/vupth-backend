package com.ospaulos.vupth.integrations.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — domain: Client Queue View
 *
 * Endpoint: GET /client-queue/{professionalId}/queue
 *           GET /client-queue/{attendanceId}/wait-time?clientId={clientId}
 *
 * Business rules (per conversation design decision):
 * - A CLIENT sees only the queue size and estimated wait time — no individual client details.
 * - A PROFESSIONAL (queue owner) can see the full queue with client details (separate endpoint).
 * - These two endpoints are the CLIENT-facing read-only views.
 *
 * Scenarios covered:
 * A) Happy path — authenticated CLIENT views professional queue overview
 * B) Happy path — CLIENT queries their own wait time in queue
 * C) 401 — no token on both endpoints
 * D) 404 — unknown professionalId / attendanceId
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClientQueueIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    String ownerProfToken, professionalId, establishmentId;
    String clientToken, clientId;
    String attendanceId;
    List<UUID> serviceItemIds;

    @BeforeAll
    void setup() throws Exception {
        log.info("[ClientQueue] setup — preparing environment");

        // Owner+Professional (solo shop)
        var ownerProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerProfToken = ownerProf.token();
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerProfToken);
        this.establishmentId = estab.id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerProfToken, establishmentId);
        this.professionalId = ownerProf.id(); // MapsId: professional.id == baseUser.id

        // Service items
        var s1 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(25));
        this.serviceItemIds = List.of(UUID.fromString(s1.id()));

        // Client registers and joins the queue
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientId = client.id();
        this.clientToken = client.token();

        AttendanceRequest req = new AttendanceRequest(
                UUID.fromString(professionalId),
                UUID.fromString(establishmentId),
                UUID.fromString(clientId),
                serviceItemIds,
                AttendanceStatus.SCHEDULED,
                OffsetDateTime.now().plusMinutes(15),
                15,
                OffsetDateTime.now());

        MvcResult created = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        this.attendanceId = new JSONObject(created.getResponse().getContentAsString()).getString("id");
        log.info("[ClientQueue] setup — estab={} prof={} client={} attendance={}",
                establishmentId, professionalId, clientId, attendanceId);
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO A — CLIENT views queue overview (size + wait, no client details)
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("GET /client-queue/{professionalId}/queue — 200 CLIENT vê visão da fila (tamanho + espera)")
    void getQueue_success() throws Exception {
        log.info("[ClientQueue] getQueue_success — professionalId={}", professionalId);

        mvc.perform(get("/client-queue/{professionalId}/queue", professionalId)
                        .param("establishmentId", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.professionalId").exists())
                .andExpect(jsonPath("$.size").isNumber())
                .andDo(print());
    }

    @Test
    @Order(11)
    @DisplayName("GET /client-queue/{professionalId}/queue — fila tem pelo menos 1 attendance (criado no setup)")
    void getQueue_hasAttendance() throws Exception {
        log.info("[ClientQueue] getQueue_hasAttendance — professionalId={}", professionalId);

        MvcResult result = mvc.perform(get("/client-queue/{professionalId}/queue", professionalId)
                        .param("establishmentId", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andDo(print())
                .andReturn();

        JSONObject json = new JSONObject(result.getResponse().getContentAsString());
        int size = json.getInt("size");
        log.info("[ClientQueue] getQueue_hasAttendance — queue size={}", size);
        assertThat(size).isGreaterThanOrEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO B — CLIENT checks own wait time
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("GET /client-queue/{attendanceId}/wait-time — 200 CLIENT vê seu tempo de espera")
    void getWaitTime_success() throws Exception {
        Assumptions.assumeTrue(attendanceId != null, "Depende do setup");
        log.info("[ClientQueue] getWaitTime_success — attendanceId={}", attendanceId);

        mvc.perform(get("/client-queue/{attendanceId}/wait-time", attendanceId)
                        .param("clientId", clientId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attendanceId").value(attendanceId))
                .andExpect(jsonPath("$.position").isNumber())
                .andExpect(jsonPath("$.estimatedWaitingTime").isNumber())
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO C — Auth errors
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("GET /client-queue/{professionalId}/queue — 401 sem token")
    void getQueue_noToken() throws Exception {
        log.info("[ClientQueue] getQueue_noToken");

        mvc.perform(get("/client-queue/{professionalId}/queue", professionalId)
                        .param("establishmentId", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(31)
    @DisplayName("GET /client-queue/{attendanceId}/wait-time — 401 sem token")
    void getWaitTime_noToken() throws Exception {
        Assumptions.assumeTrue(attendanceId != null);
        log.info("[ClientQueue] getWaitTime_noToken");

        mvc.perform(get("/client-queue/{attendanceId}/wait-time", attendanceId)
                        .param("clientId", clientId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO D — Not found
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    @DisplayName("GET /client-queue/{professionalId}/queue — 404 professional not found")
    void getQueue_professionalNotFound() throws Exception {
        UUID fakeProfId = UUID.randomUUID();
        log.info("[ClientQueue] getQueue_professionalNotFound — fakeProfId={}", fakeProfId);

        mvc.perform(get("/client-queue/{professionalId}/queue", fakeProfId)
                        .param("establishmentId", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isIn(404, 400))
                .andDo(print());
    }

    @Test
    @Order(41)
    @DisplayName("GET /client-queue/{attendanceId}/wait-time — 404 attendance not found")
    void getWaitTime_attendanceNotFound() throws Exception {
        UUID fakeAttendanceId = UUID.randomUUID();
        log.info("[ClientQueue] getWaitTime_attendanceNotFound — fakeId={}", fakeAttendanceId);

        mvc.perform(get("/client-queue/{attendanceId}/wait-time", fakeAttendanceId)
                        .param("clientId", clientId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isIn(404, 400))
                .andDo(print());
    }

    @AfterAll
    void tearDown() {
        log.info("[ClientQueue] tearDown");
        attendanceRepository.deleteAll();
        userRepository.deleteAll();
    }
}
