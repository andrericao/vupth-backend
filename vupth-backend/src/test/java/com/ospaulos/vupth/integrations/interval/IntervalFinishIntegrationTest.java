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
 * Integration tests — PATCH /api/v1/professionals/{professionalId}/intervals/{intervalId}/finish
 * (spec item #11e2 — "encerrar refeicao" manual, sem precisar atender ninguem).
 *
 * Cobre: 409 ao encerrar refeicao nao iniciada, 404 por ownership, 403 (CLIENT),
 * encerramento com sucesso (reason MANUAL, actualEndTime, isActive=false),
 * e 409 ao encerrar 2x.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntervalFinishIntegrationTest {

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
    @Order(10)
    @DisplayName("PATCH /finish — 409 refeicao nao iniciada (nada a encerrar)")
    void finish_notStarted_conflict() throws Exception {
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}/finish", profAId, lunchAId)
                        .header("Authorization", profAToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INTERVAL_CONFLICT"))
                .andDo(print());
    }

    @Test
    @Order(20)
    @DisplayName("PATCH /finish — 404 intervalo de outro profissional (ownership)")
    void finish_ownershipMismatch() throws Exception {
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}/finish", profBId, lunchAId)
                        .header("Authorization", profBToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INTERVAL_NOT_FOUND"));
    }

    @Test
    @Order(21)
    @DisplayName("PATCH /finish — 403 CLIENT nao pode encerrar")
    void finish_clientForbidden() throws Exception {
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}/finish", profAId, lunchAId)
                        .header("Authorization", clientToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(30)
    @DisplayName("PATCH /finish — 200 encerra a refeicao iniciada (reason MANUAL)")
    void finish_success() throws Exception {
        // inicia primeiro
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}/start", profAId, lunchAId)
                        .header("Authorization", profAToken))
                .andExpect(status().isOk());

        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}/finish", profAId, lunchAId)
                        .header("Authorization", profAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andDo(print());

        ProfessionalInterval finished = intervalRepository.findById(lunchAId).orElseThrow();
        assertThat(finished.getIsActive()).isFalse();
        assertThat(finished.getActualEndTime()).isNotNull();
        assertThat(finished.getAutoFinishReason()).isEqualTo("MANUAL");
    }

    @Test
    @Order(31)
    @DisplayName("PATCH /finish — 409 ao encerrar de novo (ja encerrada)")
    void finish_twice_conflict() throws Exception {
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}/finish", profAId, lunchAId)
                        .header("Authorization", profAToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INTERVAL_CONFLICT"));
    }

    @AfterAll
    void tearDown() {
        intervalRepository.deleteAll();
        userRepository.deleteAll();
    }
}
