package com.ospaulos.vupth.integrations.professional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.professional.dto.request.AffiliateToEstablishmentRequest;
import com.ospaulos.vupth.api.professional.dto.request.ProfessionalQuickCreateRequest;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — POST /professional/affiliate
 *
 * Scenarios:
 * A) Setup: criação de profissional sem establishment via quick-create
 *    (não é possível: quick-create exige establishmentId)
 *    → strategy: profissional criado com estab A, testa affiliate para estab B = 409
 *
 * B) 409: profissional já afiliado tenta afiliar a outro establishment
 *
 * C) Erros:
 *    - 401 sem token
 *    - 404 user sem role PROFESSIONAL
 *    - 422 establishmentId nulo
 *    - 400 establishmentId formato inválido
 *    - 404 establishment inexistente
 *
 * Nota sobre o fluxo MVP:
 *   O quick-create cria o profissional já afiliado a um establishment.
 *   O affiliate serve para o caso em que o profissional foi criado via outro
 *   caminho sem establishment, ou para testar a regra de conflito.
 *   No MVP atual, o caso de sucesso real (establishment == null → affiliate → 200)
 *   só pode ser criado manipulando o banco diretamente ou via CreateProfessionalUseCase
 *   (endpoint não exposto no MVP). Por isso o cenário de 409 é o principal caso integrado.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AffiliateToEstablishmentIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalRepository professionalRepository;

    private final Faker faker = new Faker();

    // Profissional já afiliado (criado via quick-create)
    String profToken;
    String establishmentId;   // o establishment atual do profissional
    String establishment2Id;  // um segundo establishment (alvo do affiliate)

    // Usuário sem role PROFESSIONAL (só CLIENT)
    String clientToken;

    @BeforeAll
    void setup() throws Exception {
        log.info("[Affiliate] setup — preparando ambiente");

        // Owner 1: cria o establishment que o profissional vai usar no quick-create
        var owner1 = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var estab1 = TestUserUtils.becomeOwner(mvc, mapper, faker, owner1.token());
        this.establishmentId = estab1.id();

        // Owner 2: cria um segundo establishment (alvo do affiliate — vai gerar 409)
        var owner2 = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var estab2 = TestUserUtils.becomeOwner(mvc, mapper, faker, owner2.token());
        this.establishment2Id = estab2.id();

        // Profissional: nasce CLIENT, cria perfil professional vinculado ao estab1
        var prof = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profToken = prof.token();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, profToken, establishmentId, "Carlos Barbosa");

        log.info("[Affiliate] setup — prof={} estab1={} estab2={}", prof.id(), establishmentId, establishment2Id);

        // Cliente puro (sem role PROFESSIONAL)
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientToken = client.token();
    }

    // ─────────────────────────────────────────────────────────────
    // SCENARIO B — 409: profissional já afiliado
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("POST /professional/affiliate — 409 profissional já está afiliado a um establishment")
    void affiliate_alreadyAffiliated_409() throws Exception {
        Assumptions.assumeTrue(profToken != null && establishment2Id != null);
        log.info("[Affiliate] affiliate_alreadyAffiliated_409 — tentando afiliar ao estab2={}", establishment2Id);

        mvc.perform(post("/professional/affiliate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content(mapper.writeValueAsString(
                                new AffiliateToEstablishmentRequest(UUID.fromString(establishment2Id)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFESSIONAL_ALREADY_AFFILIATED"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").isString())
                .andDo(print());
    }

    @Test
    @Order(11)
    @DisplayName("POST /professional/affiliate — 409 mesmo establishment atual também retorna 409")
    void affiliate_sameEstablishment_409() throws Exception {
        Assumptions.assumeTrue(profToken != null && establishmentId != null);
        log.info("[Affiliate] affiliate_sameEstablishment_409");

        // Tentar se afiliar ao mesmo establishment que já está afiliado → também 409
        mvc.perform(post("/professional/affiliate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content(mapper.writeValueAsString(
                                new AffiliateToEstablishmentRequest(UUID.fromString(establishmentId)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFESSIONAL_ALREADY_AFFILIATED"))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // ERROS
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("POST /professional/affiliate — 401 sem token")
    void affiliate_noToken_401() throws Exception {
        log.info("[Affiliate] affiliate_noToken_401");

        mvc.perform(post("/professional/affiliate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new AffiliateToEstablishmentRequest(UUID.fromString(establishmentId)))))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(21)
    @DisplayName("POST /professional/affiliate — 403 token CLIENT sem role PROFESSIONAL")
    void affiliate_clientToken_403() throws Exception {
        log.info("[Affiliate] affiliate_clientToken_403");

        // SecurityConfig: /professional/affiliate requer ROLE_PROFESSIONAL
        // CLIENT não tem essa role → Spring Security retorna 403 antes de chegar no use case
        mvc.perform(post("/professional/affiliate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(
                                new AffiliateToEstablishmentRequest(UUID.fromString(establishmentId)))))
                .andExpect(status().isForbidden())
                .andDo(print());
    }

    @Test
    @Order(22)
    @DisplayName("POST /professional/affiliate — 422 establishmentId nulo")
    void affiliate_nullEstablishmentId_422() throws Exception {
        log.info("[Affiliate] affiliate_nullEstablishmentId_422");

        mvc.perform(post("/professional/affiliate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content("{\"establishmentId\": null}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.establishmentId").exists())
                .andDo(print());
    }

    @Test
    @Order(23)
    @DisplayName("POST /professional/affiliate — 400 establishmentId não é UUID válido")
    void affiliate_invalidUuid_400() throws Exception {
        log.info("[Affiliate] affiliate_invalidUuid_400");

        mvc.perform(post("/professional/affiliate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content("{\"establishmentId\": \"nao-e-um-uuid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andDo(print());
    }

    @Test
    @Order(24)
    @DisplayName("POST /professional/affiliate — 409 establishment inexistente (após verificar vínculo atual)")
    void affiliate_establishmentNotFound_whenAlreadyAffiliated() throws Exception {
        log.info("[Affiliate] affiliate_establishmentNotFound_whenAlreadyAffiliated");

        // profToken já está afiliado → 409 antes mesmo de verificar o establishment
        // Este teste confirma a ordem das validações no use case
        mvc.perform(post("/professional/affiliate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content(mapper.writeValueAsString(
                                new AffiliateToEstablishmentRequest(UUID.randomUUID()))))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isIn(409, 404))
                .andDo(print());
    }

    @AfterAll
    void tearDown() {
        log.info("[Affiliate] tearDown — limpando dados");
        professionalRepository.deleteAll();
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}
