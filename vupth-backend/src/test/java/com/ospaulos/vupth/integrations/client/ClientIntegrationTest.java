package com.ospaulos.vupth.integrations.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — domain: Client
 *
 * Endpoint: GET /clients/{id}
 *
 * Scenarios covered:
 * - 200 happy path — own profile
 * - 200 another authenticated user reads the same profile (MVP: no ownership enforcement)
 * - 401 no token
 * - 404 unknown UUID
 * - 400 invalid UUID format in path variable
 *
 * @since v8 — coverage expanded v9
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClientIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository userRepository;

    private final Faker faker = new Faker();

    String clientToken, clientId;
    String anotherToken;

    @BeforeAll
    void setup() throws Exception {
        log.info("[Client] setup — registering test clients");
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientId = user.id();
        this.clientToken = user.token();

        var another = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.anotherToken = another.token();
        log.info("[Client] setup — clientId={}", clientId);
    }

    // ─────────────────────────────────────────────────────────────
    // Happy path
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("GET /clients/{id} — 200 retrieves own client profile")
    void getClient_success() throws Exception {
        log.info("[Client] getClient_success — clientId={}", clientId);
        mvc.perform(get("/clients/{id}", clientId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andDo(print());
    }

    @Test
    @Order(11)
    @DisplayName("GET /clients/{id} — 200 another authenticated user can read any client (MVP: no ownership gate)")
    void getClient_byAnotherUser_success() throws Exception {
        log.info("[Client] getClient_byAnotherUser_success — clientId={}", clientId);
        mvc.perform(get("/clients/{id}", clientId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", anotherToken))
                .andExpect(status().isOk())
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // Auth errors
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("GET /clients/{id} — 401 no token")
    void getClient_noToken() throws Exception {
        log.info("[Client] getClient_noToken");
        mvc.perform(get("/clients/{id}", clientId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // Not found
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("GET /clients/{id} — 404 valid UUID but unknown client")
    void getClient_notFound() throws Exception {
        String fakeId = UUID.randomUUID().toString();
        log.info("[Client] getClient_notFound — fakeId={}", fakeId);
        mvc.perform(get("/clients/{id}", fakeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken))
                .andExpect(status().isNotFound())
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // Path variable format
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    @DisplayName("GET /clients/{id} — 400 invalid UUID format in path variable")
    void getClient_invalidUuidFormat() throws Exception {
        log.info("[Client] getClient_invalidUuidFormat");
        mvc.perform(get("/clients/{id}", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PATH_VARIABLE"))
                .andDo(print());
    }

    @AfterAll
    void tearDown() {
        log.info("[Client] tearDown — cleaning up");
        userRepository.deleteAll();
    }
}
