package com.ospaulos.vupth.integrations.interval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests — domain: ProfessionalInterval
 *
 * Endpoints:
 *   POST /api/v1/professionals/{professionalId}/intervals/unexpected
 *   POST /api/v1/professionals/{professionalId}/intervals/recurring
 *   GET  /api/v1/professionals/{professionalId}/intervals
 *
 * Business rules:
 *   - Only PROFESSIONAL role can manage intervals (@PreAuthorize)
 *   - startTime must be in the future for unexpected intervals
 *   - durationMinutes must be positive (@Positive)
 *   - OffsetDateTime fields must be ISO-8601 strings (enforced by JacksonConfig)
 *
 * Scenarios:
 *   A) Unexpected interval — professional takes an unplanned break
 *   B) GET active intervals — returns list for today
 *   C) Auth errors — 401 no token, 403 CLIENT role
 *   D) Validation errors — zero duration, numeric startTime
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfessionalIntervalIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProfessionalIntervalRepository intervalRepository;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    String profToken, professionalId, establishmentId;
    String clientToken;
    OffsetDateTime createdIntervalStartTime;

    //Claude
    @BeforeAll
    void setup() throws Exception {
        log.info("[Interval] setup — creating owner+professional (solo shop)");

        var ownerProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.professionalId = ownerProf.id();
        //this.profToken = ownerProf.token();

        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerProf.token());
        this.establishmentId = estab.id();

        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerProf.token(), establishmentId);

        MvcResult login = mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/auth/login")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(
                                        new com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest(
                                                ownerProf.email(), ownerProf.password()))))
                .andExpect(status().isOk())
                .andReturn();

        this.profToken = "Bearer " + new org.json.JSONObject(login.getResponse().getContentAsString())
                .getJSONObject("tokens")
                .getString("access_token");

        // Simple CLIENT — used to test 403 on interval endpoints
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientToken = client.token();

        log.info("[Interval] setup — professionalId={} establishmentId={}", professionalId, establishmentId);
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO A — Unexpected interval
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("POST /unexpected — 201 professional creates an unplanned break")
    void createUnexpectedInterval_success() throws Exception {
        log.info("[Interval] createUnexpectedInterval_success — professionalId={}", professionalId);

        createdIntervalStartTime = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5);
        String startTime = createdIntervalStartTime
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        mvc.perform(post("/api/v1/professionals/{id}/intervals/unexpected", professionalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content("""
                                {
                                  "durationMinutes": 15,
                                  "startTime": "%s",
                                  "description": "Coffee break"
                                }
                                """.formatted(startTime)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.intervalId").exists())
                .andExpect(jsonPath("$.durationMinutes").value(15))
                .andExpect(jsonPath("$.type").value("UNEXPECTED"))
                .andDo(print());
    }

    @Test
    @Order(15)
    @DisplayName("POST /recurring — 201 professional cria intervalo recorrente")
    void createRecurringInterval_success() throws Exception {
        log.info("[Interval] createRecurringInterval_success — professionalId={}", professionalId);

        mvc.perform(post("/api/v1/professionals/{id}/intervals/recurring", professionalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content("""
                                {
                                  "durationMinutes": 60,
                                  "fixedStartTime": "14:00:00Z",
                                  "recurrencePattern": "WEEKDAYS",
                                  "description": "Cafe da tarde"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.intervalId").exists())
                .andExpect(jsonPath("$.type").value("RECURRENT"))
                .andExpect(jsonPath("$.durationMinutes").value(60))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO B — GET active intervals
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("GET /intervals — 200 returns array of intervals for today")
    void getActiveIntervals_success() throws Exception {
        log.info("[Interval] getActiveIntervals_success — professionalId={}", professionalId);

        mvc.perform(get("/api/v1/professionals/{id}/intervals", professionalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andDo(print());
    }

    @Test
    @Order(21)
    @DisplayName("GET /intervals — list contains the interval created in order 10")
    void getActiveIntervals_containsCreatedInterval() throws Exception {
        log.info("[Interval] getActiveIntervals_containsCreatedInterval");

        mvc.perform(get("/api/v1/professionals/{id}/intervals", professionalId)
                        // BUG2b: o GET fatia o dia da LOJA (BusinessClock), nao o dia UTC — perto da meia-noite
                        // UTC os dois divergem e o intervalo criado "agora" cai na fatia do dia anterior.
                        .param("date", BusinessClock.dateAt(createdIntervalStartTime).toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertThat(body).contains("UNEXPECTED");
                })
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO C — Auth errors
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("POST /unexpected — 401 no token")
    void createInterval_noToken() throws Exception {
        log.info("[Interval] createInterval_noToken");

        String startTime = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        mvc.perform(post("/api/v1/professionals/{id}/intervals/unexpected", professionalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"durationMinutes": 10, "startTime": "%s"}
                                """.formatted(startTime)))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(31)
    @DisplayName("POST /unexpected — 403 CLIENT role cannot create intervals")
    void createInterval_clientRole_forbidden() throws Exception {
        log.info("[Interval] createInterval_clientRole_forbidden");

        String startTime = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        mvc.perform(post("/api/v1/professionals/{id}/intervals/unexpected", professionalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content("""
                                {"durationMinutes": 10, "startTime": "%s"}
                                """.formatted(startTime)))
                .andExpect(status().isForbidden())
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO D — Validation errors
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    @DisplayName("POST /unexpected — 422 durationMinutes = 0 (must be positive)")
    void createInterval_zeroDuration_fails() throws Exception {
        log.info("[Interval] createInterval_zeroDuration_fails");

        String startTime = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        mvc.perform(post("/api/v1/professionals/{id}/intervals/unexpected", professionalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content("""
                                {"durationMinutes": 0, "startTime": "%s"}
                                """.formatted(startTime)))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isIn(400, 422))
                .andDo(print());
    }

    @Test
    @Order(41)
    @DisplayName("POST /unexpected — 400 startTime as number (must be ISO-8601 string)")
    void createInterval_numericStartTime_rejected() throws Exception {
        log.info("[Interval] createInterval_numericStartTime_rejected");

        mvc.perform(post("/api/v1/professionals/{id}/intervals/unexpected", professionalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content("""
                                {"durationMinutes": 10, "startTime": 99999999}
                                """))
                .andExpect(status().isBadRequest())
                .andDo(print());
    }

    @AfterAll
    void tearDown() {
        log.info("[Interval] tearDown — cleaning up");
        intervalRepository.deleteAll();
        userRepository.deleteAll();
    }
}
