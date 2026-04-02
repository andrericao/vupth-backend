package com.ospaulos.vupth.integrations.establishment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.establishment.dto.request.EstablishmentQuickCreateRequest;
import com.ospaulos.vupth.api.establishment.dto.request.EstablishmentRequest;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — domain: Establishment
 *
 * Scenarios covered:
 *
 * A) OWNER-only user (not a professional)
 *    - Creates establishment via quick-create (name only)
 *    - Cria establishment com todos os dados (EstablishmentRequest)
 *    - Find por ID
 *    - Lista todos
 *    - Find with invalid ID (unknown UUID → 404)
 *    - Creation without token (→ 401)
 *    - Creation with blank name (→ 422)
 *    - Creation with invalid UUID in body (→ 400)
 *    - Creation with status as number instead of string (→ 400)
 *
 * B) OWNER + PROFESSIONAL user (same user operates both roles)
 *    - Cria establishment via quick-create
 *    - Se vincula como profissional no mesmo establishment
 *    - Verifies both roles are active
 *
 * C) CLIENT user trying to create establishment (→ 403)
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EstablishmentIntegrationTest {

    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired UserRepository userRepository;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private final Faker faker = new Faker();

    // Scenario A — OWNER only
    String ownerToken, ownerId, establishmentId;

    // Scenario B — OWNER + PROFESSIONAL
    String ownerProfToken, ownerProfId, ownerProfEstablishmentId;

    // Scenario C — CLIENT only
    String clientToken;

    @BeforeAll
    void setup() throws Exception {
        log.info("[Establishment] setup — creating test users");

        // Scenario A: user who will create establishment (born CLIENT, becomes OWNER via endpoint)
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerId = owner.id();
        this.ownerToken = owner.token();
        log.info("[Establishment] setup — owner created: id={}", ownerId);

        // Scenario B: user who will be OWNER + PROFESSIONAL (solo shop)
        // Roles are added via endpoints, not at registration
        var ownerProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerProfId = ownerProf.id();
        this.ownerProfToken = ownerProf.token();
        log.info("[Establishment] setup — ownerProf created: id={}", ownerProfId);

        // Scenario C: simple client (born CLIENT, no further setup)
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientToken = client.token();
        log.info("[Establishment] setup — cliente created");
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO A — OWNER creates establishment
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("POST /establishment/quick-create — 201 creation with name only (minimum MVP)")
    void quickCreate_success() throws Exception {
        log.info("[Establishment] quickCreate_success — ownerId={}", ownerId);

        EstablishmentQuickCreateRequest request = new EstablishmentQuickCreateRequest(
                faker.company().name());

        MvcResult result = mvc.perform(post("/establishment/quick-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value(request.name()))
                .andExpect(jsonPath("$.owner_id").value(ownerId))
                .andDo(print())
                .andReturn();

        this.establishmentId = new JSONObject(result.getResponse().getContentAsString()).getString("id");
        log.info("[Establishment] quickCreate_success — created id={}", establishmentId);
        assertThat(establishmentId).isNotBlank();
    }

    @Test
    @Order(11)
    @DisplayName("POST /establishment/create — 201 cria com todos os dados")
    void createFull_success() throws Exception {
        log.info("[Establishment] createFull_success — ownerId={}", ownerId);

        EstablishmentRequest request = new EstablishmentRequest(
                UUID.fromString(ownerId),
                Set.of(ServiceType.BARBER),
                faker.company().name(),
                faker.internet().url(),
                EstablishmentStatus.ACTIVE,
                OpenStatus.OPEN);

        mvc.perform(post("/establishment/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").exists())
                .andDo(print());
    }

    @Test
    @Order(12)
    @DisplayName("GET /establishment/id/{id} — 200 find by valid ID")
    void findById_success() throws Exception {
        Assumptions.assumeTrue(establishmentId != null, "Depende do quickCreate_success");
        log.info("[Establishment] findById_success — id={}", establishmentId);

        mvc.perform(get("/establishment/id/{id}", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(establishmentId))
                .andDo(print());
    }

    @Test
    @Order(13)
    @DisplayName("GET /establishment — 200 lista todos, retorna array")
    void findAll_success() throws Exception {
        log.info("[Establishment] findAll_success");

        MvcResult result = mvc.perform(get("/establishment")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andDo(print())
                .andReturn();

        JSONArray array = new JSONArray(result.getResponse().getContentAsString());
        log.info("[Establishment] findAll_success — {} establishments retornados", array.length());
        assertThat(array.length()).isGreaterThan(0);
    }

    @Test
    @Order(14)
    @DisplayName("GET /establishment/id/{id}/professionals-with-queue — 200 retorna snapshot de fila")
    void findWithQueue_success() throws Exception {
        Assumptions.assumeTrue(establishmentId != null);
        log.info("[Establishment] findWithQueue_success — id={}", establishmentId);

        mvc.perform(get("/establishment/id/{id}/professionals-with-queue", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(establishmentId))
                .andExpect(jsonPath("$.professionals").isArray())
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO A — ERRORS
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("GET /establishment/id/{id} — 404 ID not found")
    void findById_notFound() throws Exception {
        UUID inexistente = UUID.randomUUID();
        log.info("[Establishment] findById_notFound — id={}", inexistente);

        mvc.perform(get("/establishment/id/{id}", inexistente)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ESTABLISHMENT_NOT_FOUND"))
                .andDo(print());
    }

    @Test
    @Order(21)
    @DisplayName("POST /establishment/quick-create — 401 sem token")
    void quickCreate_noToken() throws Exception {
        log.info("[Establishment] quickCreate_noToken");

        mvc.perform(post("/establishment/quick-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new EstablishmentQuickCreateRequest("Barbershop"))))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(22)
    @DisplayName("POST /establishment/quick-create — 422 nome em branco")
    void quickCreate_blankName() throws Exception {
        log.info("[Establishment] quickCreate_blankName");

        mvc.perform(post("/establishment/quick-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(mapper.writeValueAsString(new EstablishmentQuickCreateRequest(""))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andDo(print());
    }

    @Test
    @Order(23)
    @DisplayName("POST /establishment/quick-create — 422 nome nulo")
    void quickCreate_nullName() throws Exception {
        log.info("[Establishment] quickCreate_nullName");

        mvc.perform(post("/establishment/quick-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"name\": null}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andDo(print());
    }

    @Test
    @Order(24)
    @DisplayName("POST /establishment/create — 400 ownerID as non-UUID string")
    void createFull_ownerIdInvalidFormat() throws Exception {
        log.info("[Establishment] createFull_ownerIdInvalidFormat");

        String body = """
                {
                  "ownerID": "nao-e-um-uuid",
                  "services": ["BARBER"],
                  "name": "Teste",
                  "urlImage": "http://img.com",
                  "status": "ACTIVE",
                  "openStatus": "OPEN"
                }
                """;

        mvc.perform(post("/establishment/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andDo(print());
    }

    @Test
    @Order(25)
    @DisplayName("POST /establishment/create — 400 status as number instead of enum")
    void createFull_statusAsNumber() throws Exception {
        log.info("[Establishment] createFull_statusAsNumber");

        String body = String.format("""
                {
                  "ownerID": "%s",
                  "services": ["BARBER"],
                  "name": "Teste",
                  "urlImage": "http://img.com",
                  "status": 99,
                  "openStatus": "OPEN"
                }
                """, ownerId);

        mvc.perform(post("/establishment/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andDo(print());
    }

    @Test
    @Order(26)
    @DisplayName("POST /establishment/create — 400 services como string em vez de array")
    void createFull_servicesAsString() throws Exception {
        log.info("[Establishment] createFull_servicesAsString");

        String body = String.format("""
                {
                  "ownerID": "%s",
                  "services": "BARBER",
                  "name": "Teste",
                  "urlImage": "http://img.com",
                  "status": "ACTIVE",
                  "openStatus": "OPEN"
                }
                """, ownerId);

        mvc.perform(post("/establishment/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO B — OWNER + PROFESSIONAL (same user, two roles)
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("OWNER+PROFESSIONAL — cria establishment via quick-create")
    void ownerProf_quickCreate() throws Exception {
        log.info("[Establishment] ownerProf_quickCreate — userId={}", ownerProfId);

        EstablishmentQuickCreateRequest request = new EstablishmentQuickCreateRequest(
                "Barbershop do Dono-Profissional " + faker.number().digits(4));

        MvcResult result = mvc.perform(post("/establishment/quick-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.owner_id").value(ownerProfId))
                .andDo(print())
                .andReturn();

        this.ownerProfEstablishmentId = new JSONObject(
                result.getResponse().getContentAsString()).getString("id");
        log.info("[Establishment] ownerProf_quickCreate — establishment={}", ownerProfEstablishmentId);
    }

    @Test
    @Order(31)
    @DisplayName("OWNER+PROFESSIONAL — links as professional in their own establishment")
    void ownerProf_selfLinkAsProfessional() throws Exception {
        Assumptions.assumeTrue(ownerProfEstablishmentId != null, "Depende do ownerProf_quickCreate");
        log.info("[Establishment] ownerProf_selfLinkAsProfessional — user={} establishment={}",
                ownerProfId, ownerProfEstablishmentId);

        String body = String.format("""
                {
                  "name": "Dr. Dono",
                  "establishmentId": "%s"
                }
                """, ownerProfEstablishmentId);

        mvc.perform(post("/professional/quick-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.establishment_id").value(ownerProfEstablishmentId))
                .andDo(print());

        log.info("[Establishment] ownerProf_selfLinkAsProfessional — professional linked to their own establishment");
    }

    @Test
    @Order(32)
    @DisplayName("OWNER+PROFESSIONAL — establishment com fila aparece professional vinculado")
    void ownerProf_queueSnapshotShowsProfessional() throws Exception {
        Assumptions.assumeTrue(ownerProfEstablishmentId != null);
        log.info("[Establishment] ownerProf_queueSnapshotShowsProfessional");

        MvcResult result = mvc.perform(get("/establishment/id/{id}/professionals-with-queue",
                        ownerProfEstablishmentId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.professionals").isArray())
                .andDo(print())
                .andReturn();

        JSONObject json = new JSONObject(result.getResponse().getContentAsString());
        JSONArray professionals = json.getJSONArray("professionals");
        log.info("[Establishment] ownerProf_queueSnapshotShowsProfessional — {} profissional(is) encontrado(s)",
                professionals.length());
        assertThat(professionals.length()).isGreaterThan(0);
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO C — CLIENT trying to create establishment
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    @DisplayName("POST /establishment/quick-create — 201 CLIENT pode criar establishment e se torna OWNER")
    void clientCanCreate() throws Exception {
        // Business rule: every authenticated user already has CLIENT role.
        // Qualquer CLIENT pode criar um establishment — is assim que se torna OWNER.
        log.info("[Establishment] clientCanCreate — clientToken={}", clientToken != null);

        mvc.perform(post("/establishment/quick-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(new EstablishmentQuickCreateRequest("Barbershop do Ex-Cliente"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andDo(print());
    }

    @AfterAll
    void tearDown() {
        log.info("[Establishment] tearDown — limpando dados de teste");
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}
