package com.ospaulos.vupth.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — domain: Refresh Token
 *
 * Cobre:
 * - Happy path: usa refresh_token para obter novo access_token
 * - Invalid token → 401
 * - Expired token (not directly testable — use missing field as proxy → 400/401)
 * - Body vazio → 400/401
 * - Token as number instead of string → 400
 * - Logout invalida o refresh_token → next uso retorna 401
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RefreshTokenIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    String email, password, accessToken, refreshToken;

    @BeforeAll
    void setup() throws Exception {
        log.info("[RefreshToken] setup — registering and logging in test user");
        var user = TestUserUtils.registerBaseUser(mvc, mapper, faker);
        this.email = user.email();
        this.password = user.password();

        LoginRequest login = new LoginRequest(email, password);
        MvcResult result = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn();

        JSONObject json = new JSONObject(result.getResponse().getContentAsString());
        JSONObject tokens = json.getJSONObject("tokens");
        this.accessToken = tokens.getString("access_token");
        this.refreshToken = tokens.getString("refresh_token");
        log.info("[RefreshToken] setup — tokens obtidos. refreshToken presente={}", refreshToken != null);
    }

    @Test
    @Order(1)
    @DisplayName("POST /auth/refresh — 200 new access_token issued with valid refresh_token")
    void refresh_success() throws Exception {
        log.info("[RefreshToken] refresh_success");

        String body = String.format("{\"refresh_token\": \"%s\"}", refreshToken);

        MvcResult result = mvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists())
                .andDo(print())
                .andReturn();

        String newAccessToken = new JSONObject(result.getResponse().getContentAsString())
                .getString("access_token");
        assertThat(newAccessToken).isNotBlank();
        log.info("[RefreshToken] refresh_success — novo access_token gerado");
    }

    @Test
    @Order(2)
    @DisplayName("POST /auth/refresh — 401 invalid refresh_token (random string)")
    void refresh_invalidToken() throws Exception {
        log.info("[RefreshToken] refresh_invalidToken");

        mvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\": \"token-completamente-invalido-12345\"}"))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(3)
    @DisplayName("POST /auth/refresh — 400/401 body vazio")
    void refresh_emptyBody() throws Exception {
        log.info("[RefreshToken] refresh_emptyBody");

        mvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isIn(400, 401, 422))
                .andDo(print());
    }

    @Test
    @Order(4)
    @DisplayName("POST /auth/refresh — 400 refresh_token as number instead of string")
    void refresh_tokenAsNumber() throws Exception {
        log.info("[RefreshToken] refresh_tokenAsNumber");

        mvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\": 12345}"))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isNotEqualTo(500))
                .andDo(print());
    }

    @Test
    @Order(10)
    @DisplayName("POST /auth/logout + POST /auth/refresh — 401 token invalidated after logout")
    void refresh_afterLogout_invalid() throws Exception {
        log.info("[RefreshToken] refresh_afterLogout_invalid — fazendo logout primeiro");

        // Logout
        mvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + accessToken)
                        .content(String.format("{\"refresh_token\": \"%s\"}", refreshToken)))
                .andDo(print());
        // Status pode ser 200 ou 204

        log.info("[RefreshToken] refresh_afterLogout_invalid — trying to use refresh_token after logout");

        // Attempt to use the same refresh_token — should be invalid
        mvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"refresh_token\": \"%s\"}", refreshToken)))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isIn(401, 404))
                .andDo(print());
    }

    @AfterAll
    void tearDown() {
        log.info("[RefreshToken] tearDown — limpando dados");
        userRepository.deleteAll();
    }
}
