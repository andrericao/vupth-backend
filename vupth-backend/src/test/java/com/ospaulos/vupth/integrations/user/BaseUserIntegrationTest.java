package com.ospaulos.vupth.integrations.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.api.user.dto.request.profile.UserRegisterRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests — domain: User lookup (BaseUser read endpoints)
 *
 * Covers:
 * - POST /auth/login       → token extraction via correct JSON path (tokens.access_token)
 * - GET  /users, /users/email/{e}, /users/id/{id} → fechadas (401 sem token, 403 com token)
 * - GET  /users/me/addresses → pede login
 *
 * Setup: registers one user via TestUserUtils.registerAndLoginUser so token parsing
 * follows the definitive LoginResponse structure (tokens.access_token).
 *
 * @since v6
 * @compat Token path was fixed in v6 — BaseUser test was using json.getString("access_token")
 *         (root level) instead of json.getJSONObject("tokens").getString("access_token").
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BaseUserIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired ObjectMapper mapper;

    private final Faker faker = new Faker();

    String token, id, email, password;

    @BeforeAll
    void setup() throws Exception {
        log.info("[BaseUser] setup — registering test user");
        // Uses TestUserUtils to ensure correct JSON path handling (tokens.access_token)
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.id = user.id();
        this.token = user.token(); // already includes "Bearer "
        this.email = user.email();
        this.password = user.password();
        log.info("[BaseUser] setup — id={} email={}", id, email);
    }

    // ─────────────────────────────────────────────────────────────
    // LOGIN — verify token structure
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("POST /auth/login — 200 token extracted from tokens.access_token")
    void login_tokenExtractedCorrectly() throws Exception {
        log.info("[BaseUser] login_tokenExtractedCorrectly — email={}", email);

        MvcResult result = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                // Definitive structure: tokens object wraps access_token and refresh_token
                .andExpect(jsonPath("$.tokens.access_token").exists())
                .andExpect(jsonPath("$.tokens.refresh_token").exists())
                .andExpect(jsonPath("$.user.id").value(id))
                .andDo(print())
                .andReturn();

        // Verify token can be extracted correctly
        JSONObject json = new JSONObject(result.getResponse().getContentAsString());
        String extractedToken = "Bearer " + json.getJSONObject("tokens").getString("access_token");
        assertThat(extractedToken).startsWith("Bearer ");
        log.info("[BaseUser] login_tokenExtractedCorrectly — token extracted OK");
    }

    @Test
    @Order(2)
    @DisplayName("POST /auth/login — 401 wrong password")
    void login_wrongPassword() throws Exception {
        log.info("[BaseUser] login_wrongPassword");

        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(email, "wrongPassword999!"))))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // GET /users, /users/email/{email}, /users/id/{id} — fechadas
    // Devolviam e-mail e papeis de qualquer conta sem token e nenhum app as usa
    // (mapa-endpoint-papel, 03/10): 401 sem token, 403 com token.
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("GET /users/email/{email} — 401 sem token, 403 com token")
    void getUserByEmail_closed() throws Exception {
        mvc.perform(get("/users/email/{email}", email))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/users/email/{email}", email).header("Authorization", token))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(20)
    @DisplayName("GET /users/id/{id} — 401 sem token, 403 com token")
    void getUserById_closed() throws Exception {
        mvc.perform(get("/users/id/{id}", id))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/users/id/{id}", id).header("Authorization", token))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(30)
    @DisplayName("GET /users — 401 sem token, 403 com token")
    void getAllUsers_closed() throws Exception {
        mvc.perform(get("/users"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/users").header("Authorization", token))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(40)
    @DisplayName("GET /users/me/addresses — 401 sem token (antes caia no permitAll de /users/**)")
    void getMyAddresses_requiresLogin() throws Exception {
        mvc.perform(get("/users/me/addresses"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/users/me/addresses").header("Authorization", token))
                .andExpect(status().isOk());
    }

    @AfterAll
    void tearDown() {
        log.info("[BaseUser] tearDown — cleaning up test users");
        userRepository.deleteAll();
    }
}
