package com.ospaulos.vupth.integrations.professional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.establishment.dto.request.AddProfessionalToEstablishmentRequest;
import com.ospaulos.vupth.api.professional.dto.request.ProfessionalRegisterRequest;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — Fluxo app parceiro (dois caminhos)
 *
 * Fluxo A: "Sou profissional" (sem establishment)
 *   POST /professional/register → cria PROFESSIONAL com establishment = null
 *
 * Fluxo B: Owner adiciona profissional ao establishment
 *   POST /establishment/{id}/professionals → owner vincula o profissional
 *
 * Fluxo C: Fluxo completo integrado
 *   Profissional se registra → Owner o vincula → profissional aparece na loja
 *
 * Erros:
 *   - register sem token → 401
 *   - register já profissional → 409
 *   - addProfessional sem token → 401
 *   - addProfessional com CLIENT token → 403
 *   - addProfessional: owner errado → 403
 *   - addProfessional: profissional já afiliado → 409
 *   - addProfessional: profissional não existe → 404
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfessionalPartnerFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalRepository professionalRepository;

    private final Faker faker = new Faker();

    // Owner com establishment
    String ownerToken, establishmentId;
    // Segundo owner (para teste de acesso negado)
    String otherOwnerToken, otherEstabId;
    // Profissional registrado sem establishment
    String profToken, profId;
    // Cliente puro
    String clientToken;

    @BeforeAll
    void setup() throws Exception {
        log.info("[PartnerFlow] setup");

        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerToken = owner.token();
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerToken);
        this.establishmentId = estab.id();

        var otherOwner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.otherOwnerToken = otherOwner.token();
        var otherEstab = TestUserUtils.becomeOwner(mvc, mapper, faker, otherOwnerToken);
        this.otherEstabId = otherEstab.id();

        var prof = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profId = prof.id();
        this.profToken = prof.token();

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientToken = client.token();

        log.info("[PartnerFlow] setup — owner={} estab={} prof={}", owner.id(), establishmentId, profId);
    }

    // ─────────────────────────────────────────────────────────────
    // FLUXO A — Profissional se registra sem establishment
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("POST /professional/register — 201 profissional criado sem establishment")
    void register_success_noEstablishment() throws Exception {
        log.info("[PartnerFlow] register_success_noEstablishment");

        MvcResult result = mvc.perform(post("/professional/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content(mapper.writeValueAsString(
                                new ProfessionalRegisterRequest(faker.name().firstName(), "Corte Masculino"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.establishment_id").isEmpty())
                .andDo(print())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JSONObject json = new JSONObject(body);
        assertThat(json.getString("id")).isEqualTo(profId);
        log.info("[PartnerFlow] register_success — professional id={} sem establishment", profId);
    }

    @Test
    @Order(11)
    @DisplayName("POST /professional/register — 409 usuário já é profissional")
    void register_alreadyProfessional_409() throws Exception {
        Assumptions.assumeTrue(profToken != null);
        log.info("[PartnerFlow] register_alreadyProfessional_409");

        mvc.perform(post("/professional/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content(mapper.writeValueAsString(
                                new ProfessionalRegisterRequest("Outro Nome", null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_PROFESSIONAL"))
                .andDo(print());
    }

    @Test
    @Order(12)
    @DisplayName("POST /professional/register — 401 sem token")
    void register_noToken_401() throws Exception {
        mvc.perform(post("/professional/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new ProfessionalRegisterRequest("Carlos", null))))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(13)
    @DisplayName("POST /professional/register — 422 nome em branco")
    void register_blankName_422() throws Exception {
        var freshUser = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(post("/professional/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", freshUser.token())
                        .content(mapper.writeValueAsString(
                                new ProfessionalRegisterRequest("", null))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // FLUXO B — Owner adiciona profissional ao establishment
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("POST /establishment/{id}/professionals — 201 owner vincula profissional")
    void addProfessional_success() throws Exception {
        Assumptions.assumeTrue(profId != null && establishmentId != null);
        log.info("[PartnerFlow] addProfessional_success — owner vincula prof={} ao estab={}", profId, establishmentId);

        mvc.perform(post("/establishment/{id}/professionals", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(mapper.writeValueAsString(
                                new AddProfessionalToEstablishmentRequest(UUID.fromString(profId)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(profId))
                .andExpect(jsonPath("$.establishment_id").value(establishmentId))
                .andDo(print());

        log.info("[PartnerFlow] addProfessional_success — profissional vinculado ao establishment");
    }

    @Test
    @Order(21)
    @DisplayName("POST /establishment/{id}/professionals — 409 profissional já afiliado")
    void addProfessional_alreadyAffiliated_409() throws Exception {
        Assumptions.assumeTrue(profId != null && establishmentId != null);
        log.info("[PartnerFlow] addProfessional_alreadyAffiliated_409");

        mvc.perform(post("/establishment/{id}/professionals", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(mapper.writeValueAsString(
                                new AddProfessionalToEstablishmentRequest(UUID.fromString(profId)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFESSIONAL_ALREADY_AFFILIATED"))
                .andDo(print());
    }

    @Test
    @Order(22)
    @DisplayName("POST /establishment/{id}/professionals — 401 sem token")
    void addProfessional_noToken_401() throws Exception {
        mvc.perform(post("/establishment/{id}/professionals", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new AddProfessionalToEstablishmentRequest(UUID.fromString(profId)))))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(23)
    @DisplayName("POST /establishment/{id}/professionals — 403 token CLIENT sem role OWNER")
    void addProfessional_clientToken_403() throws Exception {
        mvc.perform(post("/establishment/{id}/professionals", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(
                                new AddProfessionalToEstablishmentRequest(UUID.fromString(profId)))))
                .andExpect(status().isForbidden())
                .andDo(print());
    }

    @Test
    @Order(24)
    @DisplayName("POST /establishment/{id}/professionals — 403 owner errado (não é dono do establishment)")
    void addProfessional_wrongOwner_403() throws Exception {
        log.info("[PartnerFlow] addProfessional_wrongOwner_403");

        // otherOwnerToken é dono de otherEstabId, não de establishmentId
        var freshProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);

        // Register fresh prof without establishment
        mvc.perform(post("/professional/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", freshProf.token())
                        .content(mapper.writeValueAsString(
                                new ProfessionalRegisterRequest("Fresh Prof", null))))
                .andExpect(status().isCreated());

        // otherOwnerToken tries to add to establishmentId (not theirs)
        mvc.perform(post("/establishment/{id}/professionals", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", otherOwnerToken)
                        .content(mapper.writeValueAsString(
                                new AddProfessionalToEstablishmentRequest(UUID.fromString(freshProf.id())))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ESTABLISHMENT_ACCESS_DENIED"))
                .andDo(print());
    }

    @Test
    @Order(25)
    @DisplayName("POST /establishment/{id}/professionals — 404 profissional inexistente")
    void addProfessional_profNotFound_404() throws Exception {
        log.info("[PartnerFlow] addProfessional_profNotFound_404");

        mvc.perform(post("/establishment/{id}/professionals", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(mapper.writeValueAsString(
                                new AddProfessionalToEstablishmentRequest(UUID.randomUUID()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROFESSIONAL_NOT_FOUND"))
                .andDo(print());
    }

    @Test
    @Order(26)
    @DisplayName("POST /establishment/{id}/professionals — 422 professionalId nulo")
    void addProfessional_nullProfId_422() throws Exception {
        mvc.perform(post("/establishment/{id}/professionals", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"professionalId\": null}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.professionalId").exists())
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // FLUXO C — Fluxo completo: register → addProfessional → aparece na fila
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("Fluxo completo: novo profissional → register → owner adiciona → aparece no establishment")
    void fullFlow_registerAndAdd() throws Exception {
        log.info("[PartnerFlow] fullFlow_registerAndAdd");

        // 1. Novo profissional se registra no app parceiro
        var newProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        MvcResult regResult = mvc.perform(post("/professional/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", newProf.token())
                        .content(mapper.writeValueAsString(
                                new ProfessionalRegisterRequest("Novo Profissional", "Manicure"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.establishment_id").isEmpty())
                .andReturn();

        String newProfId = new JSONObject(regResult.getResponse().getContentAsString()).getString("id");
        log.info("[PartnerFlow] fullFlow — profissional registrado id={}", newProfId);

        // 2. Owner de 'otherEstabId' o adiciona ao seu establishment
        mvc.perform(post("/establishment/{id}/professionals", otherEstabId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", otherOwnerToken)
                        .content(mapper.writeValueAsString(
                                new AddProfessionalToEstablishmentRequest(UUID.fromString(newProfId)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(newProfId))
                .andExpect(jsonPath("$.establishment_id").value(otherEstabId))
                .andDo(print());

        log.info("[PartnerFlow] fullFlow — profissional vinculado ao establishment={}", otherEstabId);

        // 3. Verificar que profissional NÃO pode ser adicionado novamente a outro establishment
        mvc.perform(post("/establishment/{id}/professionals", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(mapper.writeValueAsString(
                                new AddProfessionalToEstablishmentRequest(UUID.fromString(newProfId)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFESSIONAL_ALREADY_AFFILIATED"))
                .andDo(print());

        log.info("[PartnerFlow] fullFlow — regra MVP confirmada: profissional só em um establishment");
    }

    // ─────────────────────────────────────────────────────────────
    // FLUXO D — PATCH /professional/me (editar nome e especialidade)
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    @DisplayName("PATCH /professional/me — 200 atualiza nome e especialidade")
    void updateMyProfile_nameAndSpecialty() throws Exception {
        Assumptions.assumeTrue(profToken != null, "Depende do register (Order 10)");
        log.info("[PartnerFlow] updateMyProfile_nameAndSpecialty");

        mvc.perform(patch("/professional/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content("{\"name\":\"Joao Editado\",\"specialty\":\"Barbeiro Senior\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(profId))
                .andExpect(jsonPath("$.name").value("Joao Editado"))
                .andExpect(jsonPath("$.specialty").value("Barbeiro Senior"))
                .andDo(print());

        // confirma persistencia via GET /professional/me
        mvc.perform(get("/professional/me")
                        .header("Authorization", profToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Joao Editado"))
                .andExpect(jsonPath("$.specialty").value("Barbeiro Senior"))
                .andDo(print());
    }

    @Test
    @Order(41)
    @DisplayName("PATCH /professional/me — 200 update parcial: so specialty, nome preservado")
    void updateMyProfile_partialKeepsName() throws Exception {
        Assumptions.assumeTrue(profToken != null);
        log.info("[PartnerFlow] updateMyProfile_partialKeepsName");

        mvc.perform(patch("/professional/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content("{\"specialty\":\"Colorista\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Joao Editado")) // preservado do Order 40
                .andExpect(jsonPath("$.specialty").value("Colorista"))
                .andDo(print());
    }

    @Test
    @Order(42)
    @DisplayName("PATCH /professional/me — 401 sem token")
    void updateMyProfile_noToken_401() throws Exception {
        mvc.perform(patch("/professional/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\"}"))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(43)
    @DisplayName("PATCH /professional/me — 403 token CLIENT (sem ROLE_PROFESSIONAL)")
    void updateMyProfile_clientToken_403() throws Exception {
        Assumptions.assumeTrue(clientToken != null);
        log.info("[PartnerFlow] updateMyProfile_clientToken_403");

        mvc.perform(patch("/professional/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden())
                .andDo(print());
    }

    @Test
    @Order(44)
    @DisplayName("PATCH /professional/me — 422 specialty acima de 100 chars")
    void updateMyProfile_specialtyTooLong_422() throws Exception {
        Assumptions.assumeTrue(profToken != null);
        log.info("[PartnerFlow] updateMyProfile_specialtyTooLong_422");

        String longSpecialty = "a".repeat(101);
        mvc.perform(patch("/professional/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content("{\"specialty\":\"" + longSpecialty + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // FLUXO E — GET /professional?name=&specialty= (owner busca p/ convidar)
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(50)
    @DisplayName("GET /professional?specialty= — 200 owner encontra profissional por especialidade")
    void searchProfessional_bySpecialty() throws Exception {
        log.info("[PartnerFlow] searchProfessional_bySpecialty");

        // cria um profissional fresco com especialidade unica
        String uniqueSpecialty = "Esp" + faker.number().digits(6);
        var freshProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        MvcResult reg = mvc.perform(post("/professional/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", freshProf.token())
                        .content(mapper.writeValueAsString(
                                new ProfessionalRegisterRequest("Maria Busca", uniqueSpecialty))))
                .andExpect(status().isCreated())
                .andReturn();
        String freshProfId = new JSONObject(reg.getResponse().getContentAsString()).getString("id");

        // owner busca por parte da especialidade (case-insensitive)
        MvcResult result = mvc.perform(get("/professional")
                        .param("specialty", uniqueSpecialty.toLowerCase())
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").value(freshProfId))
                .andExpect(jsonPath("$[0].name").value("Maria Busca"))
                .andExpect(jsonPath("$[0].specialty").value(uniqueSpecialty))
                .andExpect(jsonPath("$[0].is_available").exists())
                .andDo(print())
                .andReturn();

        assertThat(new org.json.JSONArray(result.getResponse().getContentAsString()).length())
                .isEqualTo(1);
    }

    @Test
    @Order(51)
    @DisplayName("GET /professional — 200 sem filtros lista profissionais (owner)")
    void searchProfessional_noFilters() throws Exception {
        log.info("[PartnerFlow] searchProfessional_noFilters");

        MvcResult result = mvc.perform(get("/professional")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andDo(print())
                .andReturn();

        assertThat(new org.json.JSONArray(result.getResponse().getContentAsString()).length())
                .isGreaterThan(0);
    }

    @Test
    @Order(52)
    @DisplayName("GET /professional — 401 sem token")
    void searchProfessional_noToken_401() throws Exception {
        mvc.perform(get("/professional")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(53)
    @DisplayName("GET /professional — 403 token PROFESSIONAL (so OWNER)")
    void searchProfessional_professionalToken_403() throws Exception {
        Assumptions.assumeTrue(profToken != null);
        log.info("[PartnerFlow] searchProfessional_professionalToken_403");

        mvc.perform(get("/professional")
                        .header("Authorization", profToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andDo(print());
    }

    @AfterAll
    void tearDown() {
        log.info("[PartnerFlow] tearDown");
        professionalRepository.deleteAll();
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}
