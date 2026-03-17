package com.ospaulos.vupth.integrations.professional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.professional.dto.request.ProfessionalQuickCreateRequest;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — domain: Professional
 *
 * Scenarios:
 * A) Profissional independente vinculado a um establishment existente
 * B) Owner who is also a professional (same userId)
 * C) Erros: sem token, establishment inexistente, nome em branco,
 *           non-UUID establishmentId string, user already is a professional
 * D) Cliente tentando se tornar profissional (403)
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfessionalIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalRepository professionalRepository;

    private final Faker faker = new Faker();

    // Establishment owner (creates the establishment only)
    String ownerToken, ownerId, establishmentId;

    // Profissional independente (diferente do dono)
    String profToken, profId, professionalId;

    // Owner who is also a professional
    String ownerProfToken, ownerProfId, ownerProfEstabId;

    // Client
    String clientToken;

    @BeforeAll
    void setup() throws Exception {
        log.info("[Professional] setup — preparando ambiente");

        // User who will create the establishment (born CLIENT → becomes OWNER via endpoint)
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerId = owner.id();
        this.ownerToken = owner.token();

        // Cria o establishment — role OWNER adicionada automaticamente pelo backend
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerToken);
        this.establishmentId = estab.id();
        log.info("[Professional] setup — establishment created id={}", establishmentId);

        // Profissional independente: nasce CLIENT, vai se filiar via endpoint (vira PROFESSIONAL)
        var prof = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profId = prof.id();
        this.profToken = prof.token();
        log.info("[Professional] setup — user profissional id={}", profId);

        // Owner who is also a professional: creates establishment then links to their own
        var ownerProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerProfId = ownerProf.id();
        this.ownerProfToken = ownerProf.token();

        var ownerProfEstab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerProfToken);
        this.ownerProfEstabId = ownerProfEstab.id();
        log.info("[Professional] setup — ownerProf id={} establishment={}", ownerProfId, ownerProfEstabId);

        // Simple client: born CLIENT, no further setup
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientToken = client.token();
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO A — independent professional linked to establishment
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("POST /professional/quick-create — 201 profissional vinculado ao establishment")
    void createProfessional_success() throws Exception {
        log.info("[Professional] createProfessional_success — profId={} establishment={}",
                profId, establishmentId);

        ProfessionalQuickCreateRequest request = new ProfessionalQuickCreateRequest(
                faker.name().fullName(),
                UUID.fromString(establishmentId),
                "Corte masculino");

        MvcResult result = mvc.perform(post("/professional/quick-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content(mapper.writeValueAsString(request)))
                .andDo(print())  // print before assertion to capture 500 body
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.establishment_id").value(establishmentId))
                .andExpect(jsonPath("$.name").exists())
                .andReturn();

        JSONObject json = new JSONObject(result.getResponse().getContentAsString());
        this.professionalId = json.getString("id");
        log.info("[Professional] createProfessional_success — professionalId={}", professionalId);
        assertThat(professionalId).isNotBlank();
    }

    @Test
    @Order(11)
    @DisplayName("POST /professional/quick-create — 409 same user trying to create a second profile")
    void createProfessional_alreadyExists() throws Exception {
        Assumptions.assumeTrue(professionalId != null, "Depende do createProfessional_success");
        log.info("[Professional] createProfessional_alreadyExists — profId={}", profId);

        ProfessionalQuickCreateRequest request = new ProfessionalQuickCreateRequest(
                faker.name().fullName(),
                UUID.fromString(establishmentId),
                null);

        mvc.perform(post("/professional/quick-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_PROFESSIONAL"))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO B — OWNER who is also a professional
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("OWNER+PROFESSIONAL — links to their own establishment successfully")
    void ownerProf_linksToOwnEstablishment() throws Exception {
        log.info("[Professional] ownerProf_linksToOwnEstablishment — userId={} estab={}",
                ownerProfId, ownerProfEstabId);

        ProfessionalQuickCreateRequest request = new ProfessionalQuickCreateRequest(
                faker.name().fullName(),
                UUID.fromString(ownerProfEstabId),
                "Fade specialist");

        mvc.perform(post("/professional/quick-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.establishment_id").value(ownerProfEstabId))
                .andDo(print());

        log.info("[Professional] ownerProf_linksToOwnEstablishment — vinculado com sucesso");
    }

    @Test
    @Order(21)
    @DisplayName("OWNER+PROFESSIONAL — specialty is optional (null accepted)")
    void ownerProf_noSpecialty() throws Exception {
        // Creates a new owner+prof for this test (the previous one already created in ownerProfEstab)
        var newOwnerProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var newEstab = TestUserUtils.becomeOwner(mvc, mapper, faker, newOwnerProf.token());

        log.info("[Professional] ownerProf_noSpecialty — newOwnerProf={} estab={}", newOwnerProf.id(), newEstab.id());

        ProfessionalQuickCreateRequest request = new ProfessionalQuickCreateRequest(
                faker.name().fullName(),
                UUID.fromString(newEstab.id()),
                null); // sem especialidade

        mvc.perform(post("/professional/quick-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", newOwnerProf.token())
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    // specialty can be null or absent — both are valid in the MVP
                    assertThat(body).doesNotContain("\"specialty\":\"required\"");
                })
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // ERROS
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("POST /professional/quick-create — 401 sem token")
    void createProfessional_noToken() throws Exception {
        log.info("[Professional] createProfessional_noToken");

        mvc.perform(post("/professional/quick-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new ProfessionalQuickCreateRequest("John", UUID.randomUUID(), null))))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(31)
    @DisplayName("POST /professional/quick-create — 422 nome em branco")
    void createProfessional_blankName() throws Exception {
        log.info("[Professional] createProfessional_blankName");

        ProfessionalQuickCreateRequest request = new ProfessionalQuickCreateRequest(
                "",
                UUID.fromString(establishmentId),
                null);

        mvc.perform(post("/professional/quick-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andDo(print());
    }

    @Test
    @Order(32)
    @DisplayName("POST /professional/quick-create — 422 establishmentId nulo")
    void createProfessional_nullEstablishmentId() throws Exception {
        log.info("[Professional] createProfessional_nullEstablishmentId");

        String body = """
                {"name": "John Smith", "establishmentId": null}
                """;

        mvc.perform(post("/professional/quick-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.establishmentId").exists())
                .andDo(print());
    }

    @Test
    @Order(33)
    @DisplayName("POST /professional/quick-create — 400 establishmentId as non-UUID string")
    void createProfessional_invalidUuidFormat() throws Exception {
        log.info("[Professional] createProfessional_invalidUuidFormat");

        String body = """
                {"name": "John Smith", "establishmentId": "not-a-uuid"}
                """;

        mvc.perform(post("/professional/quick-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andDo(print());
    }

    @Test
    @Order(34)
    @DisplayName("POST /professional/quick-create — 400 establishmentId as number")
    void createProfessional_establishmentIdAsNumber() throws Exception {
        log.info("[Professional] createProfessional_establishmentIdAsNumber");

        String body = """
                {"name": "John Smith", "establishmentId": 12345}
                """;

        mvc.perform(post("/professional/quick-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO D — CLIENT without permission
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    @DisplayName("POST /professional/quick-create — 201 CLIENT pode se filiar como profissional")
    void client_canBecomeProfessional() throws Exception {
        // Business rule: every authenticated user already has CLIENT role.
        // Qualquer CLIENT pode se filiar como profissional — is assim que adquire a role PROFESSIONAL.
        log.info("[Professional] client_canBecomeProfessional");

        // Cria um client fresh (sem ser professional ainda)
        var freshClient = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);

        mvc.perform(post("/professional/quick-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", freshClient.token())
                        .content(mapper.writeValueAsString(
                                new ProfessionalQuickCreateRequest("Maria", UUID.fromString(establishmentId), null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andDo(print());
    }

    @AfterAll
    void tearDown() {
        log.info("[Professional] tearDown — limpando dados");
        professionalRepository.deleteAll();
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}
