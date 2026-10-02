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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — abrir a loja na mao exige alguem para atender (S28 do Partner; Andre, 26/09).
 *
 * <p>(a) Sem nenhum profissional "trabalhando", abrir e recusado: a loja apareceria aberta para o cliente
 * sem ninguem no local. (b) Quem abre e tambem profissional da loja (com servico) passa a "trabalhando"
 * na hora — e a loja abre com ele.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenStoreNeedsSomeoneWorkingIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository establishmentRepository;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;

    private final Faker faker = new Faker();

    String establishmentId, ownerToken, employeeToken;

    @BeforeAll
    void setup() throws Exception {
        // dono que NAO e profissional
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerToken = owner.token();
        this.establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerToken).id();
        // o gate de abrir exige horario configurado — dia inteiro, para nao depender do relogio
        TestUserUtils.setStatusAndHours(tx, establishmentRepository, establishmentId,
                com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus.CLOSED,
                java.time.LocalTime.of(0, 0), java.time.LocalTime.of(23, 59));

        // funcionario com servico, ainda Fora
        var employee = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, employee.token(), establishmentId, "Funcionario");
        this.employeeToken = employee.token();
        TestUserUtils.createServiceItem(mvc, mapper, establishmentId, employeeToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
    }

    private org.springframework.test.web.servlet.ResultActions open(String token) throws Exception {
        return mvc.perform(patch("/establishment/{id}/open-status", establishmentId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", token)
                .content("{\"openStatus\":\"OPEN\"}"));
    }

    @Test
    @Order(10)
    @DisplayName("(a) Dono abre com todo mundo Fora -> 409 ESTABLISHMENT_NOBODY_WORKING, e a loja segue fechada")
    void ownerOpens_nobodyWorking_409() throws Exception {
        open(ownerToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ESTABLISHMENT_NOBODY_WORKING"));
    }

    @Test
    @Order(20)
    @DisplayName("(b) O profissional da loja abre -> ele vira 'trabalhando' e a loja abre com ele")
    void professionalOpens_becomesWorking() throws Exception {
        open(employeeToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open_status").value("OPEN"));

        mvc.perform(get("/professional/me").header("Authorization", employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.work_status").value("WORKING"));
    }

    @Test
    @Order(30)
    @DisplayName("(a) Com alguem trabalhando, o dono consegue abrir")
    void ownerOpens_withSomeoneWorking_200() throws Exception {
        open(ownerToken).andExpect(status().isOk());
    }

    @AfterAll
    void tearDown() {
        userRepository.deleteAll();
    }
}
