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
 * Integration tests — PATCH /api/v1/professionals/{professionalId}/intervals/{intervalId}
 * (spec item #11b/c — editar refeicao / ajustar previsao de inicio).
 *
 * Usa a refeicao "almoco" semeada na criacao do profissional (item #11a) como alvo.
 *
 * Cobre: edicao (duracao + horario previsto + descricao), 404 (inexistente),
 * 404 por ownership (intervalo de outro profissional), 422 (duracao negativa),
 * 403 (CLIENT).
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntervalUpdateIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProfessionalIntervalRepository intervalRepository;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    String profAToken, profBToken, clientToken;
    UUID profAId, profBId, lunchAId;

    @BeforeAll
    void setup() throws Exception {
        // Profissional A (solo shop) — dono do estabelecimento e profissional
        var userA = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profAId = UUID.fromString(userA.id());
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, userA.token());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, userA.token(), estab.id());
        this.profAToken = relogin(userA.email(), userA.password());

        // Profissional B — filia-se ao mesmo estabelecimento (para teste de ownership)
        var userB = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profBId = UUID.fromString(userB.id());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, userB.token(), estab.id());
        this.profBToken = relogin(userB.email(), userB.password());

        // CLIENT puro — para 403
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientToken = client.token();

        // almoco semeado de A (item #11a)
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
    @DisplayName("PATCH — 200 edita duracao, horario previsto e descricao do almoco")
    void update_success() throws Exception {
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}", profAId, lunchAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profAToken)
                        .content("""
                                {"durationMinutes": 90, "fixedStartTime": "11:30:00Z", "description": "Almoco estendido"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durationMinutes").value(90))
                .andExpect(jsonPath("$.description").value("Almoco estendido"))
                .andDo(print());

        ProfessionalInterval updated = intervalRepository.findById(lunchAId).orElseThrow();
        assertThat(updated.getDurationMinutes()).isEqualTo(90);
        assertThat(updated.getFixedStartTime().getHour()).isEqualTo(11);
        assertThat(updated.getFixedStartTime().getMinute()).isEqualTo(30);
    }

    @Test
    @Order(11)
    @DisplayName("PATCH — 200 campos omitidos preservam valores (semantica PATCH)")
    void update_partial_keepsOtherFields() throws Exception {
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}", profAId, lunchAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profAToken)
                        .content("{\"durationMinutes\": 45}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durationMinutes").value(45))
                .andExpect(jsonPath("$.description").value("Almoco estendido")) // preservado
                .andDo(print());
    }

    @Test
    @Order(20)
    @DisplayName("PATCH — 404 intervalo inexistente")
    void update_notFound() throws Exception {
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}", profAId, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profAToken)
                        .content("{\"durationMinutes\": 60}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INTERVAL_NOT_FOUND"))
                .andDo(print());
    }

    @Test
    @Order(21)
    @DisplayName("PATCH — 404 intervalo de outro profissional (ownership pelo path)")
    void update_ownershipMismatch() throws Exception {
        // intervalo pertence a A, mas o path usa o id de B
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}", profBId, lunchAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profBToken)
                        .content("{\"durationMinutes\": 60}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INTERVAL_NOT_FOUND"))
                .andDo(print());
    }

    @Test
    @Order(30)
    @DisplayName("PATCH — 422 duracao negativa")
    void update_negativeDuration() throws Exception {
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}", profAId, lunchAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profAToken)
                        .content("{\"durationMinutes\": -10}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(400, 422))
                .andDo(print());
    }

    @Test
    @Order(31)
    @DisplayName("PATCH — 403 CLIENT nao pode editar intervalo")
    void update_clientForbidden() throws Exception {
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}", profAId, lunchAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content("{\"durationMinutes\": 60}"))
                .andExpect(status().isForbidden())
                .andDo(print());
    }

    @AfterAll
    void tearDown() {
        intervalRepository.deleteAll();
        userRepository.deleteAll();
    }
}
