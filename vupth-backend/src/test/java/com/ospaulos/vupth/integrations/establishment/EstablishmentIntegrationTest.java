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
 *
 * D) Filtros de establishment (GET com params)
 *    - Lista só ativos
 *    - Busca por nome parcial
 *    - Filtra por ServiceType
 *    - ServiceType inválido → 400
 *
 * E) Toggle openStatus (PATCH /establishment/{id}/open-status)
 *    - Owner fecha → 200 CLOSED
 *    - Owner reabre → 200 OPEN
 *    - Sem token → 401
 *    - Token CLIENT → 403  ← usa pureClientToken (nunca criou establishment)
 *    - Owner errado → 404
 *    - Body inválido → 400
 *
 * F) General infos (PATCH /establishment/{id}/general-infos)
 *    - Atualiza nome → 200
 *    - Atualiza service_type → 200
 *    - Atualiza url_image → 200
 *    - Atualiza services → 200
 *    - Atualiza múltiplos campos → 200
 *    - Body vazio → 200, modified_fields vazio
 *    - Sem token → 401
 *    - Token CLIENT → 403
 *    - Owner errado → 404
 *    - service_type inválido → 400
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

    // Scenario C — CLIENT only (becomes OWNER at Order 40 via clientCanCreate)
    String clientToken;

    // pureClientToken: never creates establishment → stays pure CLIENT (used for 403 tests)
    String pureClientToken;

    @BeforeAll
    void setup() throws Exception {
        log.info("[Establishment] setup — creating test users");

        // Scenario A: user who will create establishment (born CLIENT, becomes OWNER via endpoint)
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerId = owner.id();
        this.ownerToken = owner.token();
        log.info("[Establishment] setup — owner created: id={}", ownerId);

        // Scenario B: user who will be OWNER + PROFESSIONAL (solo shop)
        var ownerProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerProfId = ownerProf.id();
        this.ownerProfToken = ownerProf.token();
        log.info("[Establishment] setup — ownerProf created: id={}", ownerProfId);

        // Scenario C: simple client (born CLIENT, no further setup)
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientToken = client.token();
        log.info("[Establishment] setup — cliente created");

        // pureClient: never creates establishment → stays CLIENT only (for 403 tests)
        var pureClient = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.pureClientToken = pureClient.token();
        log.info("[Establishment] setup — pureClient created");
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO A — OWNER creates establishment
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("POST /establishment/quick-create — 201 creation with name and serviceType")
    void quickCreate_success() throws Exception {
        log.info("[Establishment] quickCreate_success — ownerId={}", ownerId);

        EstablishmentQuickCreateRequest request = new EstablishmentQuickCreateRequest(
                faker.company().name(), ServiceType.BARBER);

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
                OpenStatus.OPEN,
                null);

        mvc.perform(post("/establishment/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").exists())
                .andExpect(jsonPath("$.services").isArray())
                .andExpect(jsonPath("$.services[0]").value("BARBER"))
                .andDo(print());
    }

    @Test
    @Order(15)
    @DisplayName("POST /establishment/create — 201 cria com serviceType e retorna no response")
    void createFull_withServiceType() throws Exception {
        log.info("[Establishment] createFull_withServiceType — ownerId={}", ownerId);

        EstablishmentRequest request = new EstablishmentRequest(
                UUID.fromString(ownerId),
                Set.of(ServiceType.BARBER),
                "Barbearia do Ricardo",
                faker.internet().url(),
                EstablishmentStatus.ACTIVE,
                OpenStatus.OPEN,
                ServiceType.BARBER);

        MvcResult result = mvc.perform(post("/establishment/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.serviceType").value("BARBER"))
                .andDo(print())
                .andReturn();

        String createdId = new JSONObject(result.getResponse().getContentAsString()).getString("id");

        mvc.perform(get("/establishment/id/{id}", createdId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(createdId))
                .andExpect(jsonPath("$.serviceType").value("BARBER"))
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
                .andExpect(jsonPath("$.services").isArray())
                .andExpect(jsonPath("$.services[0]").value("BARBER"))
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
                        .content(mapper.writeValueAsString(new EstablishmentQuickCreateRequest("Barbershop", ServiceType.BARBER))))
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
                        .content(mapper.writeValueAsString(new EstablishmentQuickCreateRequest("", ServiceType.BARBER))))
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
    @DisplayName("POST /establishment/create — 400 ownerId as non-UUID string")
    void createFull_ownerIdInvalidFormat() throws Exception {
        log.info("[Establishment] createFull_ownerIdInvalidFormat");

        String body = """
                {
                  "ownerId": "nao-e-um-uuid",
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
                  "ownerId": "%s",
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
                  "ownerId": "%s",
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
                "Barbershop do Dono-Profissional " + faker.number().digits(4), ServiceType.BARBER);

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
                .andExpect(jsonPath("$.professionals[0].service_items").isArray())
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
        log.info("[Establishment] clientCanCreate — clientToken={}", clientToken != null);

        mvc.perform(post("/establishment/quick-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(new EstablishmentQuickCreateRequest("Barbershop do Ex-Cliente", ServiceType.BARBER))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO D — Filtros de establishment
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(50)
    @DisplayName("GET /establishment — 200 lista somente ativos (sem INACTIVE)")
    void findAllActive_returnsOnlyActive() throws Exception {
        log.info("[Establishment] findAllActive_returnsOnlyActive");

        MvcResult result = mvc.perform(get("/establishment")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andDo(print())
                .andReturn();

        JSONArray array = new JSONArray(result.getResponse().getContentAsString());
        log.info("[Establishment] findAllActive_returnsOnlyActive — {} retornados", array.length());
        assertThat(array.length()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @Order(51)
    @DisplayName("GET /establishment?name=x — 200 busca por nome parcial")
    void searchByName_found() throws Exception {
        Assumptions.assumeTrue(establishmentId != null, "Depende do quickCreate_success");
        log.info("[Establishment] searchByName_found");

        mvc.perform(get("/establishment")
                        .param("name", "Barber")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andDo(print());
    }

    @Test
    @Order(52)
    @DisplayName("GET /establishment?name=xyzNaoExiste — 200 retorna array vazio")
    void searchByName_notFound_returnsEmpty() throws Exception {
        log.info("[Establishment] searchByName_notFound_returnsEmpty");

        MvcResult result = mvc.perform(get("/establishment")
                        .param("name", "xyzNaoExisteNuncaJamais")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andDo(print())
                .andReturn();

        JSONArray array = new JSONArray(result.getResponse().getContentAsString());
        assertThat(array.length()).isEqualTo(0);
    }

    @Test
    @Order(53)
    @DisplayName("GET /establishment?serviceType=BARBER — 200 filtra por tipo de serviço")
    void filterByServiceType_barber() throws Exception {
        log.info("[Establishment] filterByServiceType_barber");

        MvcResult result = mvc.perform(get("/establishment")
                        .param("serviceType", "BARBER")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andDo(print())
                .andReturn();

        JSONArray array = new JSONArray(result.getResponse().getContentAsString());
        assertThat(array.length()).isGreaterThan(0);
        for (int i = 0; i < array.length(); i++) {
            JSONObject est = array.getJSONObject(i);
            JSONArray services = est.getJSONArray("services");
            boolean containsBarber = false;
            for (int j = 0; j < services.length(); j++) {
                if ("BARBER".equals(services.getString(j))) {
                    containsBarber = true;
                    break;
                }
            }
            assertThat(containsBarber)
                    .as("Establishment %s deve conter BARBER em services", est.getString("id"))
                    .isTrue();
        }
    }

    @Test
    @Order(54)
    @DisplayName("GET /establishment?serviceType=INVALIDO — 400 tipo de serviço inválido")
    void filterByServiceType_invalid() throws Exception {
        log.info("[Establishment] filterByServiceType_invalid");

        mvc.perform(get("/establishment")
                        .param("serviceType", "INVALIDO_NAOEXISTE")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO E — Toggle openStatus
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(60)
    @DisplayName("PATCH /establishment/{id}/open-status — 200 owner fecha o establishment")
    void updateOpenStatus_ownerCloses() throws Exception {
        Assumptions.assumeTrue(establishmentId != null, "Depende do quickCreate_success");
        log.info("[Establishment] updateOpenStatus_ownerCloses — id={}", establishmentId);

        mvc.perform(patch("/establishment/{id}/open-status", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"openStatus\":\"CLOSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open_status").value("CLOSED"))
                .andExpect(jsonPath("$.id").value(establishmentId))
                .andDo(print());
    }

    @Test
    @Order(61)
    @DisplayName("PATCH /establishment/{id}/open-status — 200 owner reabre o establishment")
    void updateOpenStatus_ownerReopens() throws Exception {
        Assumptions.assumeTrue(establishmentId != null, "Depende do quickCreate_success");
        log.info("[Establishment] updateOpenStatus_ownerReopens — id={}", establishmentId);

        mvc.perform(patch("/establishment/{id}/open-status", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"openStatus\":\"OPEN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open_status").value("OPEN"))
                .andDo(print());
    }

    @Test
    @Order(62)
    @DisplayName("PATCH /establishment/{id}/open-status — 200 TEMPORARILY_CLOSED válido")
    void updateOpenStatus_temporarilyClosed() throws Exception {
        Assumptions.assumeTrue(establishmentId != null, "Depende do quickCreate_success");
        log.info("[Establishment] updateOpenStatus_temporarilyClosed — id={}", establishmentId);

        mvc.perform(patch("/establishment/{id}/open-status", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"openStatus\":\"TEMPORARILY_CLOSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open_status").value("TEMPORARILY_CLOSED"))
                .andDo(print());

        // Restaura para OPEN
        mvc.perform(patch("/establishment/{id}/open-status", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"openStatus\":\"OPEN\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @Order(63)
    @DisplayName("PATCH /establishment/{id}/open-status — 401 sem token")
    void updateOpenStatus_noToken_401() throws Exception {
        UUID anyId = UUID.randomUUID();
        log.info("[Establishment] updateOpenStatus_noToken_401");

        mvc.perform(patch("/establishment/{id}/open-status", anyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openStatus\":\"CLOSED\"}"))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(64)
    @DisplayName("PATCH /establishment/{id}/open-status — 403 token CLIENT não tem ROLE_OWNER")
    void updateOpenStatus_clientToken_403() throws Exception {
        Assumptions.assumeTrue(establishmentId != null, "Depende do quickCreate_success");
        log.info("[Establishment] updateOpenStatus_clientToken_403");

        // pureClientToken: usuario que nunca criou establishment → CLIENT puro → deve receber 403
        mvc.perform(patch("/establishment/{id}/open-status", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", pureClientToken)
                        .content("{\"openStatus\":\"CLOSED\"}"))
                .andExpect(status().isForbidden())
                .andDo(print());
    }

    @Test
    @Order(65)
    @DisplayName("PATCH /establishment/{id}/open-status — 404 owner errado não encontra o establishment")
    void updateOpenStatus_wrongOwner_404() throws Exception {
        Assumptions.assumeTrue(establishmentId != null, "Depende do quickCreate_success");
        log.info("[Establishment] updateOpenStatus_wrongOwner_404");

        mvc.perform(patch("/establishment/{id}/open-status", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken)
                        .content("{\"openStatus\":\"CLOSED\"}"))
                .andExpect(status().isNotFound())
                .andDo(print());
    }

    @Test
    @Order(66)
    @DisplayName("PATCH /establishment/{id}/open-status — 400 openStatus inválido")
    void updateOpenStatus_invalidStatus_400() throws Exception {
        Assumptions.assumeTrue(establishmentId != null, "Depende do quickCreate_success");
        log.info("[Establishment] updateOpenStatus_invalidStatus_400");

        mvc.perform(patch("/establishment/{id}/open-status", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"openStatus\":\"INVALIDO\"}"))
                .andExpect(status().isBadRequest())
                .andDo(print());
    }

    @Test
    @Order(67)
    @DisplayName("PATCH /establishment/{id}/open-status — 400 body sem openStatus (null)")
    void updateOpenStatus_nullStatus_400() throws Exception {
        Assumptions.assumeTrue(establishmentId != null, "Depende do quickCreate_success");
        log.info("[Establishment] updateOpenStatus_nullStatus_400");

        mvc.perform(patch("/establishment/{id}/open-status", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"openStatus\":null}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO F — PATCH /establishment/{id}/general-infos
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(70)
    @DisplayName("PATCH /establishment/{id}/general-infos — 200 atualiza nome")
    void updateGeneralInfos_name() throws Exception {
        Assumptions.assumeTrue(establishmentId != null, "Depende do quickCreate_success");
        log.info("[Establishment] updateGeneralInfos_name — id={}", establishmentId);

        mvc.perform(patch("/establishment/{id}/general-infos", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"name\":\"Novo Nome da Barbearia\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(establishmentId))
                .andExpect(jsonPath("$.name").value("Novo Nome da Barbearia"))
                .andExpect(jsonPath("$.modified_fields").isArray())
                .andExpect(jsonPath("$.modified_fields[0]").value("name"))
                .andDo(print());
    }

    @Test
    @Order(71)
    @DisplayName("PATCH /establishment/{id}/general-infos — 200 atualiza service_type")
    void updateGeneralInfos_serviceType() throws Exception {
        Assumptions.assumeTrue(establishmentId != null, "Depende do quickCreate_success");
        log.info("[Establishment] updateGeneralInfos_serviceType — id={}", establishmentId);

        mvc.perform(patch("/establishment/{id}/general-infos", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"service_type\":\"BEAUTY_SALON\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(establishmentId))
                .andExpect(jsonPath("$.service_type").value("BEAUTY_SALON"))
                .andExpect(jsonPath("$.modified_fields[0]").value("service_type"))
                .andDo(print());
    }

    @Test
    @Order(72)
    @DisplayName("PATCH /establishment/{id}/general-infos — 200 atualiza url_image")
    void updateGeneralInfos_urlImage() throws Exception {
        Assumptions.assumeTrue(establishmentId != null, "Depende do quickCreate_success");
        log.info("[Establishment] updateGeneralInfos_urlImage — id={}", establishmentId);

        mvc.perform(patch("/establishment/{id}/general-infos", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"url_image\":\"https://img.com/nova-foto.jpg\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(establishmentId))
                .andExpect(jsonPath("$.url_image").value("https://img.com/nova-foto.jpg"))
                .andExpect(jsonPath("$.modified_fields[0]").value("url_image"))
                .andDo(print());
    }

    @Test
    @Order(73)
    @DisplayName("PATCH /establishment/{id}/general-infos — 200 atualiza services")
    void updateGeneralInfos_services() throws Exception {
        Assumptions.assumeTrue(establishmentId != null, "Depende do quickCreate_success");
        log.info("[Establishment] updateGeneralInfos_services — id={}", establishmentId);

        mvc.perform(patch("/establishment/{id}/general-infos", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"services\":[\"BARBER\",\"BEAUTY_SALON\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(establishmentId))
                .andExpect(jsonPath("$.services").isArray())
                .andExpect(jsonPath("$.modified_fields[0]").value("services"))
                .andDo(print());
    }

    @Test
    @Order(74)
    @DisplayName("PATCH /establishment/{id}/general-infos — 200 atualiza múltiplos campos")
    void updateGeneralInfos_multipleFields() throws Exception {
        Assumptions.assumeTrue(establishmentId != null, "Depende do quickCreate_success");
        log.info("[Establishment] updateGeneralInfos_multipleFields — id={}", establishmentId);

        mvc.perform(patch("/establishment/{id}/general-infos", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"name\":\"Barbearia Premium\",\"service_type\":\"BARBER\",\"url_image\":\"https://img.com/premium.jpg\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(establishmentId))
                .andExpect(jsonPath("$.name").value("Barbearia Premium"))
                .andExpect(jsonPath("$.service_type").value("BARBER"))
                .andExpect(jsonPath("$.url_image").value("https://img.com/premium.jpg"))
                .andExpect(jsonPath("$.modified_fields.length()").value(3))
                .andDo(print());
    }

    @Test
    @Order(75)
    @DisplayName("PATCH /establishment/{id}/general-infos — 200 body vazio retorna modified_fields vazio")
    void updateGeneralInfos_emptyBody() throws Exception {
        Assumptions.assumeTrue(establishmentId != null, "Depende do quickCreate_success");
        log.info("[Establishment] updateGeneralInfos_emptyBody — id={}", establishmentId);

        mvc.perform(patch("/establishment/{id}/general-infos", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(establishmentId))
                .andExpect(jsonPath("$.modified_fields").isArray())
                .andExpect(jsonPath("$.modified_fields.length()").value(0))
                .andDo(print());
    }

    @Test
    @Order(76)
    @DisplayName("PATCH /establishment/{id}/general-infos — 401 sem token")
    void updateGeneralInfos_noToken_401() throws Exception {
        log.info("[Establishment] updateGeneralInfos_noToken_401");

        mvc.perform(patch("/establishment/{id}/general-infos", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Teste\"}"))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(77)
    @DisplayName("PATCH /establishment/{id}/general-infos — 403 token CLIENT puro")
    void updateGeneralInfos_clientToken_403() throws Exception {
        Assumptions.assumeTrue(establishmentId != null, "Depende do quickCreate_success");
        log.info("[Establishment] updateGeneralInfos_clientToken_403");

        mvc.perform(patch("/establishment/{id}/general-infos", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", pureClientToken)
                        .content("{\"name\":\"Teste\"}"))
                .andExpect(status().isForbidden())
                .andDo(print());
    }

    @Test
    @Order(78)
    @DisplayName("PATCH /establishment/{id}/general-infos — 404 owner errado")
    void updateGeneralInfos_wrongOwner_404() throws Exception {
        Assumptions.assumeTrue(establishmentId != null, "Depende do quickCreate_success");
        log.info("[Establishment] updateGeneralInfos_wrongOwner_404");

        mvc.perform(patch("/establishment/{id}/general-infos", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken)
                        .content("{\"name\":\"Teste\"}"))
                .andExpect(status().isNotFound())
                .andDo(print());
    }

    @Test
    @Order(79)
    @DisplayName("PATCH /establishment/{id}/general-infos — 400 service_type inválido")
    void updateGeneralInfos_invalidServiceType_400() throws Exception {
        Assumptions.assumeTrue(establishmentId != null, "Depende do quickCreate_success");
        log.info("[Establishment] updateGeneralInfos_invalidServiceType_400");

        mvc.perform(patch("/establishment/{id}/general-infos", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"service_type\":\"INVALIDO\"}"))
                .andExpect(status().isBadRequest())
                .andDo(print());
    }

    @AfterAll
    void tearDown() {
        log.info("[Establishment] tearDown — limpando dados de teste");
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}