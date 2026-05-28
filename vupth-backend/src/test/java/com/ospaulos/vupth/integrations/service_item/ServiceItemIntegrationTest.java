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

    String ownerToken, establishmentId, serviceItemId;
    String clientToken;

    @BeforeAll
    void setup() throws Exception {
        log.info("[ServiceItem] setup — preparando owner+professional (solo shop) e establishment");

        // Owner: born CLIENT → creates establishment via endpoint → becomes OWNER automatically
        // Then links as PROFESSIONAL to their own establishment (solo shop).
        // createServiceItem endpoint requires OWNER role (security) and PROFESSIONAL profile (service layer).
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerToken = owner.token();

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

    @AfterAll
    void tearDown() {
        log.info("[ServiceItem] tearDown — limpando dados");
        serviceItemRepository.deleteAll();
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}
