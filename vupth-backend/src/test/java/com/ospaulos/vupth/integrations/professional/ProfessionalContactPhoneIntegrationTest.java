package com.ospaulos.vupth.integrations.professional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — contato do profissional (pedido de 26/09, contato-do-profissional).
 *
 * O profissional escolhe no PATCH /professional/me o telefone/WhatsApp que o cliente ve na tela dele;
 * o campo sai em GET /establishment/id/{id}/professionals-with-queue e no read-back do /professional/me.
 * Sem escolha, nao expoe nada (nem o telefone da conta).
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfessionalContactPhoneIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ServiceItemRepository serviceItemRepository;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    String ownerProfToken, establishmentId;

    @BeforeAll
    void setup() throws Exception {
        var ownerProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerProfToken = ownerProf.token();

        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerProfToken);
        this.establishmentId = estab.id();

        // solo shop: o mesmo usuario vira profissional (baseUser compartilhado)
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerProfToken, establishmentId, "Roberto");

        // service item para o profissional aparecer na fila (gate #31)
        TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
    }

    @Test
    @Order(10)
    @DisplayName("GET professionals-with-queue — sem escolha do profissional, contact_phone vem null")
    void withQueue_noContactByDefault() throws Exception {
        mvc.perform(get("/establishment/id/{id}/professionals-with-queue", establishmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.professionals[0].contact_phone").value(nullValue()))
                .andDo(print());
    }

    @Test
    @Order(20)
    @DisplayName("PATCH /professional/me — 200 grava o contato e devolve no read-back")
    void patchMe_setsContactPhone() throws Exception {
        mvc.perform(patch("/professional/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken)
                        .content("{\"contact_phone\":\"+55 (11) 98888-7777\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contact_phone").value("+55 (11) 98888-7777"))
                .andDo(print());

        mvc.perform(get("/professional/me")
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contact_phone").value("+55 (11) 98888-7777"));
    }

    @Test
    @Order(21)
    @DisplayName("GET professionals-with-queue — o cliente ve o contato que o profissional escolheu")
    void withQueue_exposesContactPhone() throws Exception {
        mvc.perform(get("/establishment/id/{id}/professionals-with-queue", establishmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.professionals[0].contact_phone").value("+55 (11) 98888-7777"))
                .andDo(print());
    }

    @Test
    @Order(22)
    @DisplayName("PATCH /professional/me — so a especialidade nao apaga o contato")
    void patchMe_otherFieldKeepsContact() throws Exception {
        mvc.perform(patch("/professional/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken)
                        .content("{\"specialty\":\"Barba\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contact_phone").value("+55 (11) 98888-7777"));
    }

    @Test
    @Order(30)
    @DisplayName("PATCH /professional/me — 400 contato com letra")
    void patchMe_invalidContact_400() throws Exception {
        mvc.perform(patch("/professional/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken)
                        .content("{\"contact_phone\":\"liga pra mim\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(40)
    @DisplayName("PATCH /professional/me — contato em branco deixa de expor")
    void patchMe_blankClearsContact() throws Exception {
        mvc.perform(patch("/professional/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken)
                        .content("{\"contact_phone\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contact_phone").value(nullValue()));

        mvc.perform(get("/establishment/id/{id}/professionals-with-queue", establishmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.professionals[0].contact_phone").value(nullValue()));
    }

    // ─────────────────────────────────────────────────────────────
    // expor-posicao (decisao de 05/09) — o interruptor mora no mesmo PATCH /professional/me
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(50)
    @DisplayName("show_position vem true por padrao no professionals-with-queue e no /professional/me")
    void showPosition_defaultTrue() throws Exception {
        mvc.perform(get("/establishment/id/{id}/professionals-with-queue", establishmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.professionals[0].show_position").value(true));
        mvc.perform(get("/professional/me").header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.show_position").value(true));
    }

    @Test
    @Order(51)
    @DisplayName("PATCH show_position=false esconde; o cliente le false e o tempo estimado continua")
    void showPosition_toggleOff() throws Exception {
        mvc.perform(patch("/professional/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken)
                        .content("{\"show_position\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.show_position").value(false));

        mvc.perform(get("/establishment/id/{id}/professionals-with-queue", establishmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.professionals[0].show_position").value(false))
                .andExpect(jsonPath("$.professionals[0].estimated_wait_minutes").isNumber());
    }

    @Test
    @Order(52)
    @DisplayName("PATCH sem show_position nao mexe; show_position=true volta a mostrar")
    void showPosition_untouchedThenOn() throws Exception {
        mvc.perform(patch("/professional/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken)
                        .content("{\"specialty\":\"Corte\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.show_position").value(false));

        mvc.perform(patch("/professional/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken)
                        .content("{\"show_position\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.show_position").value(true));
    }

    @AfterAll
    void tearDown() {
        serviceItemRepository.deleteAll();
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}
