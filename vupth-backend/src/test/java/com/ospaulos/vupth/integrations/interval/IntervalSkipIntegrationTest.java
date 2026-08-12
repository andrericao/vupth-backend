package com.ospaulos.vupth.integrations.interval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.domain.shared.BusinessClock;
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
 * Integration tests — PATCH/DELETE .../intervals/{intervalId}/skip (spec item #41 — "Nao almocar").
 *
 * Usa a refeicao "almoco" semeada na criacao do profissional (item #11a). Cobre: pular o dia
 * (200 + persiste skip_date), idempotencia, desfazer (DELETE), 404 por ownership, 403 (CLIENT),
 * 409 quando a refeicao ja foi iniciada hoje (usar finish).
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntervalSkipIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProfessionalIntervalRepository intervalRepository;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    String profAToken, profBToken, clientToken;
    UUID profAId, profBId, lunchAId, dinnerAId;

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
        this.dinnerAId = intervalRepository.findAll().stream()
                .filter(i -> i.getProfessional().getId().equals(profAId))
                .filter(i -> i.getFixedStartTime() != null && i.getFixedStartTime().getHour() == 18)
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
    @DisplayName("PATCH /skip — 404 intervalo de outro profissional (ownership)")
    void skip_ownershipMismatch() throws Exception {
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}/skip", profBId, lunchAId)
                        .header("Authorization", profBToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INTERVAL_NOT_FOUND"))
                .andDo(print());
    }

    @Test
    @Order(11)
    @DisplayName("PATCH /skip — 403 CLIENT nao pode pular")
    void skip_clientForbidden() throws Exception {
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}/skip", profAId, lunchAId)
                        .header("Authorization", clientToken))
                .andExpect(status().isForbidden())
                .andDo(print());
    }

    @Test
    @Order(20)
    @DisplayName("PATCH /skip — 200 pula o almoco de hoje (persiste skip_date)")
    void skip_success() throws Exception {
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}/skip", profAId, lunchAId)
                        .header("Authorization", profAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intervalId").value(lunchAId.toString()))
                .andDo(print());

        // BUG2b: o skip e gravado com BusinessClock.today() (dia da LOJA). Assertar em UTC quebra
        // entre 21h e 00h de Sao Paulo (= 00h-03h UTC), que e quando o CI costuma rodar.
        assertThat(intervalRepository.existsSkipForDate(lunchAId, BusinessClock.today())).isTrue();
    }

    @Test
    @Order(21)
    @DisplayName("PATCH /skip — idempotente: pular de novo o mesmo dia segue 200")
    void skip_idempotent() throws Exception {
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}/skip", profAId, lunchAId)
                        .header("Authorization", profAToken))
                .andExpect(status().isOk());
    }

    @Test
    @Order(30)
    @DisplayName("DELETE /skip — 200 desfaz o skip do dia (refeicao volta ao ETA)")
    void unskip_success() throws Exception {
        mvc.perform(delete("/api/v1/professionals/{pid}/intervals/{iid}/skip", profAId, lunchAId)
                        .header("Authorization", profAToken))
                .andExpect(status().isOk())
                .andDo(print());

        assertThat(intervalRepository.existsSkipForDate(lunchAId, BusinessClock.today())).isFalse();
    }

    @Test
    @Order(40)
    @DisplayName("PATCH /skip — 409 quando a refeicao ja foi iniciada hoje (usar finish)")
    void skip_alreadyStartedToday_conflict() throws Exception {
        // inicia a janta hoje
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}/start", profAId, dinnerAId)
                        .header("Authorization", profAToken))
                .andExpect(status().isOk());

        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}/skip", profAId, dinnerAId)
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
