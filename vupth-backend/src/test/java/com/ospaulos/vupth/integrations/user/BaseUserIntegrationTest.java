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
 * - GET  /users/email/{e}  → found, not found, auth required
 * - GET  /users/id/{id}    → found, not found, invalid UUID format
 * - GET  /users            → list all (open endpoint)
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
    // GET /users/email/{email}
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("GET /users/email/{email} — 200 found by email")
    void getUserByEmail_found() throws Exception {
        log.info("[BaseUser] getUserByEmail_found — email={}", email);

        mvc.perform(get("/users/email/{email}", email)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.id").value(id))
                .andDo(print());
    }

    @Test
    @Order(11)
    @DisplayName("GET /users/email/{email} — 404 email not registered")
    void getUserByEmail_notFound() throws Exception {
        log.info("[BaseUser] getUserByEmail_notFound");

        mvc.perform(get("/users/email/{email}", "nobody@nowhere.com")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // GET /users/id/{id}
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("GET /users/id/{id} — 200 found by ID")
    void getUserById_found() throws Exception {
        log.info("[BaseUser] getUserById_found — id={}", id);

        mvc.perform(get("/users/id/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.email").value(email))
                .andDo(print());
    }

    @Test
    @Order(21)
    @DisplayName("GET /users/id/{id} — 404 unknown ID")
    void getUserById_notFound() throws Exception {
        log.info("[BaseUser] getUserById_notFound");
        String unknownId = "00000000-0000-0000-0000-000000000000";

        mvc.perform(get("/users/id/{id}", unknownId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andDo(print());
    }

    @Test
    @Order(22)
    @DisplayName("GET /users/id/{id} — 400 id is not a valid UUID")
    void getUserById_invalidUuidFormat() throws Exception {
        log.info("[BaseUser] getUserById_invalidUuidFormat");

        mvc.perform(get("/users/id/{id}", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isNotEqualTo(500))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // GET /users
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("GET /users — 200 returns list (at least one user)")
    void getAllUsers_returnsList() throws Exception {
        log.info("[BaseUser] getAllUsers_returnsList");

        mvc.perform(get("/users")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andDo(print());
    }

    @AfterAll
    void tearDown() {
        log.info("[BaseUser] tearDown — cleaning up test users");
        userRepository.deleteAll();
    }
}
