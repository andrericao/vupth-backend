package com.ospaulos.vupth.integrations.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.api.user.dto.request.profile.UserRegisterRequest;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests — domain: Authentication (register + login + refresh)
 *
 * Covers:
 *
 * POST /auth/register:
 * - 201 success — id, email returned, password not exposed
 * - 409 duplicate email
 * - 422 null email
 * - 422 null password
 * - not-500 email as number (Jackson coerces integer to String "12345" — valid email format
 *   is checked later; result is 201 or 422, never 500)
 * - 400 body is an array instead of object
 * - 400 malformed JSON
 *
 * POST /auth/login:
 * - 200 success — tokens.access_token and tokens.refresh_token present
 * - 401 wrong password
 * - 401 email not registered
 * - not-500 empty body
 * - not-500 password as boolean (Jackson coercion — result is 401 or 400, never 500)
 *
 * POST /auth/refresh:
 * - 200 new access token issued
 * - 401 invalid refresh token
 * - not-500 missing refresh_token field
 *
 * @since v6
 * @compat register_emailAsNumber was previously expected to return 400.
 *         Jackson by default coerces numeric literals to String, so 12345 becomes "12345".
 *         The field has no @Email annotation on UserRegisterRequest, so this registers as
 *         a user with email "12345" (201) rather than failing deserialization (400).
 *         The test was updated to assert "not 500" — the definitive fix would be to add
 *         @Email on UserRegisterRequest.email, which would make this return 422. Tracked.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository repository;

    private final Faker faker = new Faker();

    String id, email, password, accessToken, refreshToken;

    @BeforeAll
    void setup() {
        log.info("[Auth] setup — generating test credentials");
        this.email = faker.internet().emailAddress();
        this.password = faker.internet().password(8, 20);
        log.info("[Auth] setup — email={}", this.email);
    }

    // ─────────────────────────────────────────────────────────────
    // REGISTER
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("POST /auth/register — 201 created successfully")
    void register_success() throws Exception {
        log.info("[Auth] register_success — email={}", email);

        MvcResult result = mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new UserRegisterRequest(email, password))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.password").doesNotExist()) // password must never be exposed
                .andDo(print())
                .andReturn();

        this.id = new JSONObject(result.getResponse().getContentAsString()).getString("id");
        assertThat(this.id).isNotBlank();
        log.info("[Auth] register_success — user created id={}", this.id);
    }

    @Test
    @Order(2)
    @DisplayName("POST /auth/register — 409 duplicate email")
    void register_duplicateEmail() throws Exception {
        log.info("[Auth] register_duplicateEmail — email={}", email);

        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new UserRegisterRequest(email, password))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_AVAILABLE"))
                .andDo(print());
    }

    @Test
    @Order(3)
    @DisplayName("POST /auth/register — 422 null email")
    void register_nullEmail() throws Exception {
        log.info("[Auth] register_nullEmail");

        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": null, \"password\": \"secret123\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andDo(print());
    }

    @Test
    @Order(4)
    @DisplayName("POST /auth/register — 422 null password")
    void register_nullPassword() throws Exception {
        log.info("[Auth] register_nullPassword");

        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"new@example.com\", \"password\": null}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.password").exists())
                .andDo(print());
    }

    /**
     * Jackson coerces numeric literal 12345 → String "12345" before validation.
     * With @Email on UserRegisterRequest.email, "12345" fails email format validation
     * → 422 VALIDATION_ERROR with a fieldErrors.email entry.
     *
     * @compat was "not 500" before @Email was added. Fixed in v8 with @Email annotation.
     */
    @Test
    @Order(5)
    @DisplayName("POST /auth/register — 422 when email is a number (coerced to String, fails @Email)")
    void register_emailAsNumber_returns422() throws Exception {
        log.info("[Auth] register_emailAsNumber_returns422");

        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": 12345, \"password\": \"secret123\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andDo(print());
    }

    @Test
    @Order(6)
    @DisplayName("POST /auth/register — 400 body is an array instead of object")
    void register_bodyIsArray() throws Exception {
        log.info("[Auth] register_bodyIsArray");

        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"this\", \"is\", \"not\", \"an\", \"object\"]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andDo(print());
    }

    @Test
    @Order(7)
    @DisplayName("POST /auth/register — 400 malformed JSON")
    void register_malformedJson() throws Exception {
        log.info("[Auth] register_malformedJson");

        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{email: no_quotes, password: 'wrong'}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // LOGIN
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("POST /auth/login — 200 success, tokens issued")
    void login_success() throws Exception {
        log.info("[Auth] login_success — email={}", email);

        MvcResult result = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokens.access_token").exists())
                .andExpect(jsonPath("$.tokens.refresh_token").exists())
                .andExpect(jsonPath("$.user.id").value(id))
                .andExpect(jsonPath("$.user.email").value(email))
                .andDo(print())
                .andReturn();

        JSONObject tokens = new JSONObject(result.getResponse().getContentAsString())
                .getJSONObject("tokens");
        this.accessToken = "Bearer " + tokens.getString("access_token");
        this.refreshToken = tokens.getString("refresh_token");

        assertThat(accessToken).startsWith("Bearer ");
        assertThat(refreshToken).isNotBlank();
        log.info("[Auth] login_success — tokens extracted OK");
    }

    @Test
    @Order(11)
    @DisplayName("POST /auth/login — 401 wrong password")
    void login_wrongPassword() throws Exception {
        log.info("[Auth] login_wrongPassword — email={}", email);

        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(email, "wrongPassword!99"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"))
                .andDo(print());
    }

    @Test
    @Order(12)
    @DisplayName("POST /auth/login — 401 email not registered")
    void login_emailNotFound() throws Exception {
        log.info("[Auth] login_emailNotFound");

        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest("nobody@example.com", "anyPassword"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_FOUND"))
                .andDo(print());
    }

    @Test
    @Order(13)
    @DisplayName("POST /auth/login — not-500 empty body ({} → null credentials → BadCredentials)")
    void login_emptyBody_notServerError() throws Exception {
        log.info("[Auth] login_emptyBody_notServerError");

        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isIn(400, 401))
                .andDo(print());
    }

    @Test
    @Order(14)
    @DisplayName("POST /auth/login — not-500 password as boolean (Jackson coercion)")
    void login_passwordAsBoolean_notServerError() throws Exception {
        log.info("[Auth] login_passwordAsBoolean_notServerError");

        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"test@example.com\", \"password\": true}"))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isNotEqualTo(500))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // REFRESH TOKEN
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("POST /auth/refresh — 200 new access token issued")
    void refreshToken_success() throws Exception {
        log.info("[Auth] refreshToken_success — refreshToken present={}", refreshToken != null);
        Assumptions.assumeTrue(refreshToken != null, "Requires login_success to pass first");

        mvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"refresh_token\": \"%s\"}", refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists())
                .andDo(print());
    }

    @Test
    @Order(21)
    @DisplayName("POST /auth/refresh — 401 invalid token")
    void refreshToken_invalid() throws Exception {
        log.info("[Auth] refreshToken_invalid");

        mvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\": \"completely-invalid-token-xyz\"}"))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(22)
    @DisplayName("POST /auth/refresh — not-500 missing refresh_token field")
    void refreshToken_missingField_notServerError() throws Exception {
        log.info("[Auth] refreshToken_missingField_notServerError");

        mvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isIn(400, 401, 422))
                .andDo(print());
    }


    // ─────────────────────────────────────────────────────────────
    // LOGOUT
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("POST /auth/logout — 200 revokes refresh token")
    void logout_success() throws Exception {
        Assumptions.assumeTrue(refreshToken != null, "Requires login_success to pass first");
        log.info("[Auth] logout_success — using refreshToken");

        mvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", accessToken)
                        .content("{\"refresh_token\": \"" + refreshToken + "\"}"))
                .andExpect(status().isNoContent())
                .andDo(print());
    }

    @Test
    @Order(31)
    @DisplayName("POST /auth/logout — 422 missing refresh_token field")
    void logout_missingToken_validation() throws Exception {
        log.info("[Auth] logout_missingToken_validation");

        mvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", accessToken != null ? accessToken : "Bearer dummy")
                        .content("{}"))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isIn(400, 422))
                .andDo(print());
    }

    @AfterAll
    void tearDown() {
        log.info("[Auth] tearDown — cleaning up test users");
        repository.deleteAll();
    }
}
