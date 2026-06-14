package com.ospaulos.vupth.integrations.service_item;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.serviceItem.dto.request.ServiceItemRequest;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — domain: ServiceItem
 *
 * Cobre:
 * - Happy path creation
 * - Erros: sem token, price como string, price negativo,
 *          invalid serviceItemType, blank name, non-existent establishment
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ServiceItemIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ServiceItemRepository serviceItemRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    String ownerToken, ownerId, establishmentId, serviceItemId;
    String clientToken;

    @BeforeAll
    void setup() throws Exception {
        log.info("[ServiceItem] setup — preparando owner+professional (solo shop) e establishment");

        // Owner: born CLIENT → creates establishment via endpoint → becomes OWNER automatically
        // Then links as PROFESSIONAL to their own establishment (solo shop).
        // createServiceItem endpoint requires OWNER role (security) and PROFESSIONAL profile (service layer).
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerToken = owner.token();
        this.ownerId = owner.id();

        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
        this.establishmentId = estab.id();

        // Same user links as PROFESSIONAL in their own establishment (solo shop pattern)
        TestUserUtils.becomeProfessional(mvc, mapper, faker, owner.token(), estab.id());
        log.info("[ServiceItem] setup — owner+professional solo shop, establishment={}", establishmentId);

        // Simple client (born with CLIENT role, no further setup)
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientToken = client.token();
    }

    @Test
    @Order(1)
    @DisplayName("POST /establishment/{id}/service-items — 201 service item created successfully")
    void createServiceItem_success() throws Exception {
        log.info("[ServiceItem] createServiceItem_success — estab={}", establishmentId);

        ServiceItemRequest request = new ServiceItemRequest(
                UUID.fromString(establishmentId),
                ServiceItemType.CORTE_MAQUINA,
                "Corte Degradê",
                BigDecimal.valueOf(35.00),
                30);

        MvcResult result = mvc.perform(post("/establishment/{id}/service-items", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.serviceItemType").value("CORTE_MAQUINA"))
                .andExpect(jsonPath("$.customName").value("Corte Degradê"))
                .andExpect(jsonPath("$.establishmentId").value(establishmentId))
                .andExpect(jsonPath("$.price").value(35.00))
                .andDo(print())
                .andReturn();

        this.serviceItemId = new JSONObject(result.getResponse().getContentAsString()).getString("id");
        log.info("[ServiceItem] createServiceItem_success — serviceItemId={}", serviceItemId);
        assertThat(serviceItemId).isNotBlank();
    }

    @Test
    @Order(2)
    @DisplayName("POST /establishment/{id}/service-items — 401 sem token")
    void createServiceItem_noToken() throws Exception {
        log.info("[ServiceItem] createServiceItem_noToken");

        ServiceItemRequest request = new ServiceItemRequest(
                UUID.fromString(establishmentId),
                ServiceItemType.CORTE_MAQUINA,
                "Outro Corte",
                BigDecimal.valueOf(25),
                30);

        mvc.perform(post("/establishment/{id}/service-items", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(3)
    @DisplayName("POST /establishment/{id}/service-items — 400 price como string")
    void createServiceItem_priceAsString() throws Exception {
        log.info("[ServiceItem] createServiceItem_priceAsString");

        String body = String.format("""
                {
                  "establishmentId": "%s",
                  "serviceItemType": "CORTE_MAQUINA",
                  "customName": "Corte X",
                  "price": "trinta reais"
                }
                """, establishmentId);

        mvc.perform(post("/establishment/{id}/service-items", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andDo(print());
    }

    @Test
    @Order(4)
    @DisplayName("POST /establishment/{id}/service-items — 400 serviceItemType with invalid enum value")
    void createServiceItem_invalidType() throws Exception {
        log.info("[ServiceItem] createServiceItem_invalidType");

        String body = String.format("""
                {
                  "establishmentId": "%s",
                  "serviceItemType": "TIPO_QUE_NAO_EXISTE",
                  "customName": "Corte X",
                  "price": 30
                }
                """, establishmentId);

        mvc.perform(post("/establishment/{id}/service-items", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andDo(print());
    }

    @Test
    @Order(5)
    @DisplayName("POST /establishment/{id}/service-items — 400 establishmentId as non-UUID string")
    void createServiceItem_invalidEstablishmentId() throws Exception {
        log.info("[ServiceItem] createServiceItem_invalidEstablishmentId");

        String body = """
                {
                  "establishmentId": "nao-e-uuid",
                  "serviceItemType": "CORTE_MAQUINA",
                  "customName": "Corte X",
                  "price": 30
                }
                """;

        mvc.perform(post("/establishment/{id}/service-items", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andDo(print());
    }

    @Test
    @Order(6)
    @DisplayName("POST /establishment/{id}/service-items — 403 CLIENT without permission")
    void createServiceItem_clientForbidden() throws Exception {
        log.info("[ServiceItem] createServiceItem_clientForbidden");

        ServiceItemRequest request = new ServiceItemRequest(
                UUID.fromString(establishmentId),
                ServiceItemType.CORTE_MAQUINA,
                "Corte Cliente",
                BigDecimal.valueOf(20),
                30);

        mvc.perform(post("/establishment/{id}/service-items", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // #10 — Editar e remover ServiceItem do profissional
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("PATCH /professional-queue/{profId}/service-items/{itemId} — 200 edita nome e preco")
    void updateServiceItem_success() throws Exception {
        Assumptions.assumeTrue(serviceItemId != null, "Depende do createServiceItem_success");
        log.info("[ServiceItem] updateServiceItem_success — item={}", serviceItemId);

        mvc.perform(patch("/professional-queue/{profId}/service-items/{itemId}", ownerId, serviceItemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"customName\":\"Corte Degradê Premium\",\"price\":45.00,\"estimatedMinutes\":40}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(serviceItemId))
                .andExpect(jsonPath("$.customName").value("Corte Degradê Premium"))
                .andExpect(jsonPath("$.price").value(45.00))
                .andDo(print());
    }

    @Test
    @Order(11)
    @DisplayName("PATCH service-items — 404 item inexistente")
    void updateServiceItem_notFound_404() throws Exception {
        log.info("[ServiceItem] updateServiceItem_notFound_404");

        mvc.perform(patch("/professional-queue/{profId}/service-items/{itemId}", ownerId, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"price\":50.00}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SERVICE_ITEM_NOT_FOUND"))
                .andDo(print());
    }

    @Test
    @Order(12)
    @DisplayName("PATCH service-items — 403 token CLIENT (sem ROLE_PROFESSIONAL)")
    void updateServiceItem_clientToken_403() throws Exception {
        Assumptions.assumeTrue(serviceItemId != null);
        log.info("[ServiceItem] updateServiceItem_clientToken_403");

        mvc.perform(patch("/professional-queue/{profId}/service-items/{itemId}", ownerId, serviceItemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content("{\"price\":1.00}"))
                .andExpect(status().isForbidden())
                .andDo(print());
    }

    @Test
    @Order(13)
    @DisplayName("PATCH service-items — 403 outro profissional nao pode editar item alheio")
    void updateServiceItem_otherProfessional_403() throws Exception {
        Assumptions.assumeTrue(serviceItemId != null);
        log.info("[ServiceItem] updateServiceItem_otherProfessional_403");

        // outro profissional (solo shop proprio)
        var other = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var otherEstab = TestUserUtils.becomeOwner(mvc, mapper, faker, other.token());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, other.token(), otherEstab.id());

        mvc.perform(patch("/professional-queue/{profId}/service-items/{itemId}", ownerId, serviceItemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", other.token())
                        .content("{\"price\":9.99}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ESTABLISHMENT_ACCESS_DENIED"))
                .andDo(print());
    }

    @Test
    @Order(14)
    @DisplayName("PATCH service-items — 422 preco negativo")
    void updateServiceItem_negativePrice_422() throws Exception {
        Assumptions.assumeTrue(serviceItemId != null);
        log.info("[ServiceItem] updateServiceItem_negativePrice_422");

        mvc.perform(patch("/professional-queue/{profId}/service-items/{itemId}", ownerId, serviceItemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"price\":-5.00}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andDo(print());
    }

    @Test
    @Order(15)
    @DisplayName("DELETE /professional-queue/{profId}/service-items/{itemId} — 204 remove; depois 404")
    void deleteServiceItem_success() throws Exception {
        Assumptions.assumeTrue(serviceItemId != null);
        log.info("[ServiceItem] deleteServiceItem_success — item={}", serviceItemId);

        mvc.perform(delete("/professional-queue/{profId}/service-items/{itemId}", ownerId, serviceItemId)
                        .header("Authorization", ownerToken))
                .andExpect(status().isNoContent())
                .andDo(print());

        // remover de novo → 404 (ja nao existe)
        mvc.perform(delete("/professional-queue/{profId}/service-items/{itemId}", ownerId, serviceItemId)
                        .header("Authorization", ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SERVICE_ITEM_NOT_FOUND"))
                .andDo(print());
    }

    @AfterAll
    void tearDown() {
        log.info("[ServiceItem] tearDown — limpando dados");
        serviceItemRepository.deleteAll();
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}
