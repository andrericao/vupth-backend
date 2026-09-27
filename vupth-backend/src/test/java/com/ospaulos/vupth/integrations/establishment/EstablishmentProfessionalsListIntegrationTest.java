package com.ospaulos.vupth.integrations.establishment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — GET /establishment/{id}/professionals: todos os vinculados, inclusive sem servico
 * (Partner S8). O professionals-with-queue esconde quem nao tem servico; aqui o dono ve o recem-vinculado.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EstablishmentProfessionalsListIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    String establishmentId, ownerToken, employeeToken, strangerToken;

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerToken = owner.token();
        this.establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerToken).id();

        var employee = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, employee.token(), establishmentId, "Recem Vinculado");
        this.employeeToken = employee.token();

        var stranger = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var other = TestUserUtils.becomeOwner(mvc, mapper, faker, stranger.token());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, stranger.token(), other.id(), "Estranho");
        this.strangerToken = stranger.token();
    }

    @Test
    @Order(10)
    @DisplayName("Dono ve o profissional recem-vinculado SEM servico (has_service false, service_count 0)")
    void owner_seesProfessionalWithoutService() throws Exception {
        mvc.perform(get("/establishment/{id}/professionals", establishmentId).header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Recem Vinculado"))
                .andExpect(jsonPath("$[0].has_service").value(false))
                .andExpect(jsonPath("$[0].service_count").value(0))
                .andExpect(jsonPath("$[0].work_status").exists());
    }

    @Test
    @Order(20)
    @DisplayName("Depois de cadastrar servico, o proprio profissional ve has_service true")
    void professional_afterService_hasService() throws Exception {
        TestUserUtils.createServiceItem(mvc, mapper, establishmentId, employeeToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));

        mvc.perform(get("/establishment/{id}/professionals", establishmentId).header("Authorization", employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].has_service").value(true))
                .andExpect(jsonPath("$[0].service_count").value(1));
    }

    @Test
    @Order(30)
    @DisplayName("Profissional de OUTRA loja -> 403")
    void stranger_forbidden() throws Exception {
        mvc.perform(get("/establishment/{id}/professionals", establishmentId).header("Authorization", strangerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(40)
    @DisplayName("Sem token -> 401 (a lista de gestao nao e publica)")
    void noToken_401() throws Exception {
        mvc.perform(get("/establishment/{id}/professionals", establishmentId))
                .andExpect(status().isUnauthorized());
    }

    @AfterAll
    void tearDown() {
        userRepository.deleteAll();
    }
}
