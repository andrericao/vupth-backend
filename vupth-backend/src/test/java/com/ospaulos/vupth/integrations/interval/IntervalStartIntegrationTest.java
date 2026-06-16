package com.ospaulos.vupth.integrations.interval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests — PATCH /api/v1/professionals/{professionalId}/intervals/{intervalId}/start
 * (spec item #11d-start — "iniciar almoco/janta").
 *
 * Usa a refeicao "almoco" semeada na criacao do profissional (item #11a).
 *
 * Cobre: inicio (marca actualStartTime/isActive, passa a aparecer no GET),
 * 409 ao iniciar 2x no mesmo dia, 404 por ownership, 403 (CLIENT).
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntervalStartIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProfessionalIntervalRepository intervalRepository;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    String profAToken, profBToken, clientToken;
    UUID profAId, profBId, lunchAId;

    @BeforeAll
    void setup() throws Exception {
        var userA = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profAId = UUID.fromString(userA.id());
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, userA.token());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, userA.token(), estab.id());
        this.profAToken = relogin(userA.email(), userA.password());

        var userB = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profBId = UUID.fromString(userB.id());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, userB.token(), estab.id());
        this.profBToken = relogin(userB.email(), userB.password());

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientToken = client.token();

        this.lunchAId = intervalRepository.findAll().stream()
                .filter(i -> i.getProfessional().getId().equals(profAId))
                .filter(i -> i.getFixedStartTime() != null && i.getFixedStartTime().getHour() == 12)
                .map(ProfessionalInterval::getId)
                .findFirst().orElseThrow();
    }

    private String relogin(String email, String password) throws Exception {
        MvcResult login = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();
        return "Bearer " + new JSONObject(login.getResponse().getContentAsString())
                .getJSONObject("tokens").getString("access_token");
    }

    @Test
    @Order(20)
    @DisplayName("PATCH /start — 404 intervalo de outro profissional (ownership)")
    void start_ownershipMismatch() throws Exception {
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}/start", profBId, lunchAId)
                        .header("Authorization", profBToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INTERVAL_NOT_FOUND"))
                .andDo(print());
    }

    @Test
    @Order(21)
    @DisplayName("PATCH /start — 403 CLIENT nao pode iniciar")
    void start_clientForbidden() throws Exception {
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}/start", profAId, lunchAId)
                        .header("Authorization", clientToken))
                .andExpect(status().isForbidden())
                .andDo(print());
    }

    @Test
    @Order(30)
    @DisplayName("PATCH /start — 200 inicia o almoco (actualStartTime + isActive)")
    void start_success() throws Exception {
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}/start", profAId, lunchAId)
                        .header("Authorization", profAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intervalId").value(lunchAId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andDo(print());

        ProfessionalInterval started = intervalRepository.findById(lunchAId).orElseThrow();
        assertThat(started.getIsActive()).isTrue();
        assertThat(started.getActualStartTime()).isNotNull();
    }

    @Test
    @Order(31)
    @DisplayName("GET /intervals — almoco iniciado passa a aparecer na lista")
    void startedInterval_appearsInGet() throws Exception {
        mvc.perform(get("/api/v1/professionals/{pid}/intervals", profAId)
                        .header("Authorization", profAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.intervalId == '" + lunchAId + "')]").exists())
                .andDo(print());
    }

    @Test
    @Order(32)
    @DisplayName("PATCH /start — 409 ao iniciar a mesma refeicao 2x no mesmo dia")
    void start_twiceSameDay_conflict() throws Exception {
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}/start", profAId, lunchAId)
                        .header("Authorization", profAToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INTERVAL_CONFLICT"))
                .andDo(print());
    }

    @AfterAll
    void tearDown() {
        intervalRepository.deleteAll();
        userRepository.deleteAll();
    }
}
