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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
                30, null, null);

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
                30, null, null);

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
                30, null, null);

        mvc.perform(post("/establishment/{id}/service-items", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andDo(print());
    }

    @Test
    @Order(7)
    @DisplayName("POST /establishment/{id}/service-items — 201 profissional AFILIADO (nao-owner) cadastra o proprio servico (S1)")
    void createServiceItem_byAffiliatedProfessional_success() throws Exception {
        // Novo usuario que se afilia como PROFESSIONAL a loja do owner (nao e o owner).
        var affiliated = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, affiliated.token(), establishmentId, "Afiliado");

        ServiceItemRequest request = new ServiceItemRequest(
                UUID.fromString(establishmentId),
                ServiceItemType.BARBA,
                "Barba do Afiliado",
                BigDecimal.valueOf(28.00),
                20, null, null);

        mvc.perform(post("/establishment/{id}/service-items", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", affiliated.token())
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.customName").value("Barba do Afiliado"))
                // o servico fica sob o establishment da loja (derivado do professional do afiliado)
                .andExpect(jsonPath("$.establishmentId").value(establishmentId))
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

    @Test
    @Order(16)
    @DisplayName("#3: cria service item com client_price e uses_platform_pricing e os retorna")
    void createServiceItem_withPricingFields() throws Exception {
        log.info("[ServiceItem] createServiceItem_withPricingFields");

        String body = String.format("""
                {
                  "establishmentId": "%s",
                  "serviceItemType": "CORTE_TESOURA",
                  "customName": "Corte com Precificacao %s",
                  "price": 40.00,
                  "estimatedMinutes": 30,
                  "client_price": 42.90,
                  "uses_platform_pricing": true
                }
                """, establishmentId, faker.number().digits(4));

        MvcResult result = mvc.perform(post("/establishment/{id}/service-items", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.price").value(40.00))
                .andExpect(jsonPath("$.client_price").value(42.90))
                .andExpect(jsonPath("$.uses_platform_pricing").value(true))
                .andDo(print())
                .andReturn();

        String itemId = new JSONObject(result.getResponse().getContentAsString()).getString("id");

        // PATCH atualiza client_price
        mvc.perform(patch("/professional-queue/{profId}/service-items/{itemId}", ownerId, itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"client_price\":49.90,\"uses_platform_pricing\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.client_price").value(49.90))
                .andExpect(jsonPath("$.uses_platform_pricing").value(false))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // Backlog #1 — Editar/remover ServiceItem pela otica do establishment
    //   PUT/DELETE /establishment/{establishmentId}/service-items/{itemId}
    // ─────────────────────────────────────────────────────────────

    /** Cria um service item na loja informada e devolve o id. */
    private String createItem(String estabId, String token, String name) throws Exception {
        String body = String.format("""
                {
                  "establishmentId": "%s",
                  "serviceItemType": "CORTE_MAQUINA",
                  "customName": "%s",
                  "price": 30.00,
                  "estimatedMinutes": 30
                }
                """, estabId, name);

        MvcResult result = mvc.perform(post("/establishment/{id}/service-items", estabId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return new JSONObject(result.getResponse().getContentAsString()).getString("id");
    }

    @Test
    @Order(20)
    @DisplayName("PUT /establishment/{id}/service-items/{itemId} — 200 owner edita nome e preco")
    void updateItemForEstablishment_ownerSuccess() throws Exception {
        String itemId = createItem(establishmentId, ownerToken, "Servico Estab " + faker.number().digits(4));
        log.info("[ServiceItem] updateItemForEstablishment_ownerSuccess — item={}", itemId);

        mvc.perform(put("/establishment/{estabId}/service-items/{itemId}", establishmentId, itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"customName\":\"Servico Editado pelo Owner\",\"price\":55.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(itemId))
                .andExpect(jsonPath("$.customName").value("Servico Editado pelo Owner"))
                .andExpect(jsonPath("$.price").value(55.00))
                .andExpect(jsonPath("$.establishmentId").value(establishmentId))
                .andDo(print());
    }

    @Test
    @Order(21)
    @DisplayName("PUT /establishment/{id}/service-items/{itemId} — 404 item inexistente")
    void updateItemForEstablishment_notFound_404() throws Exception {
        mvc.perform(put("/establishment/{estabId}/service-items/{itemId}", establishmentId, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"price\":50.00}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SERVICE_ITEM_NOT_FOUND"))
                .andDo(print());
    }

    @Test
    @Order(22)
    @DisplayName("PUT /establishment/{id}/service-items/{itemId} — 403 profissional alheio (nem owner nem dono)")
    void updateItemForEstablishment_otherProfessional_403() throws Exception {
        String itemId = createItem(establishmentId, ownerToken, "Servico Alvo " + faker.number().digits(4));

        // outro profissional dono da propria loja — nem owner desta loja nem dono do item
        var other = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var otherEstab = TestUserUtils.becomeOwner(mvc, mapper, faker, other.token());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, other.token(), otherEstab.id());

        mvc.perform(put("/establishment/{estabId}/service-items/{itemId}", establishmentId, itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", other.token())
                        .content("{\"price\":9.99}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ESTABLISHMENT_ACCESS_DENIED"))
                .andDo(print());
    }

    @Test
    @Order(23)
    @DisplayName("PUT /establishment/{id}/service-items/{itemId} — 404 item existe mas nao pertence a esta loja")
    void updateItemForEstablishment_wrongEstablishment_404() throws Exception {
        // item de OUTRA loja
        var other = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var otherEstab = TestUserUtils.becomeOwner(mvc, mapper, faker, other.token());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, other.token(), otherEstab.id());
        String foreignItem = createItem(otherEstab.id(), other.token(), "Servico Alheio " + faker.number().digits(4));

        // owner desta loja tentando editar via o path DESTA loja um item que e de outra
        mvc.perform(put("/establishment/{estabId}/service-items/{itemId}", establishmentId, foreignItem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"price\":9.99}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SERVICE_ITEM_NOT_FOUND"))
                .andDo(print());
    }

    @Test
    @Order(24)
    @DisplayName("PUT /establishment/{id}/service-items/{itemId} — 409 nome duplicado na loja")
    void updateItemForEstablishment_duplicateName_409() throws Exception {
        String nameA = "Servico Unico A " + faker.number().digits(4);
        createItem(establishmentId, ownerToken, nameA);
        String itemB = createItem(establishmentId, ownerToken, "Servico Unico B " + faker.number().digits(4));

        // renomeia B para o nome de A (case-insensitive) -> 409
        mvc.perform(put("/establishment/{estabId}/service-items/{itemId}", establishmentId, itemB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(String.format("{\"customName\":\"%s\"}", nameA.toLowerCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_ITEM_ALREADY_EXISTS"))
                .andDo(print());
    }

    @Test
    @Order(25)
    @DisplayName("DELETE /establishment/{id}/service-items/{itemId} — 204 owner remove; depois 404")
    void deleteItemForEstablishment_ownerSuccess() throws Exception {
        String itemId = createItem(establishmentId, ownerToken, "Servico p/ Remover " + faker.number().digits(4));

        mvc.perform(delete("/establishment/{estabId}/service-items/{itemId}", establishmentId, itemId)
                        .header("Authorization", ownerToken))
                .andExpect(status().isNoContent())
                .andDo(print());

        // remover de novo -> 404
        mvc.perform(delete("/establishment/{estabId}/service-items/{itemId}", establishmentId, itemId)
                        .header("Authorization", ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SERVICE_ITEM_NOT_FOUND"))
                .andDo(print());
    }

    @Test
    @Order(26)
    @DisplayName("PUT /establishment/{id}/service-items/{itemId} — 200 proprio profissional afiliado edita seu item")
    void updateItemForEstablishment_ownItemByAffiliated_success() throws Exception {
        // profissional afiliado (nao-owner) cadastra o proprio servico e depois o edita
        var affiliated = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, affiliated.token(), establishmentId, "AfiliadoEdit");
        String itemId = createItem(establishmentId, affiliated.token(), "Servico do Afiliado " + faker.number().digits(4));

        mvc.perform(put("/establishment/{estabId}/service-items/{itemId}", establishmentId, itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", affiliated.token())
                        .content("{\"price\":33.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(itemId))
                .andExpect(jsonPath("$.price").value(33.00))
                .andDo(print());
    }

    @Test
    @Order(27)
    @DisplayName("DELETE /establishment/{id}/service-items/{itemId} — 403 CLIENT sem role")
    void deleteItemForEstablishment_clientToken_403() throws Exception {
        String itemId = createItem(establishmentId, ownerToken, "Servico Protegido " + faker.number().digits(4));

        mvc.perform(delete("/establishment/{estabId}/service-items/{itemId}", establishmentId, itemId)
                        .header("Authorization", clientToken))
                .andExpect(status().isForbidden())
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
