package com.ospaulos.vupth.integrations.establishment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — acesso ao open-status (abrir/fechar a loja).
 *
 * Decisao de produto: alem do dono, o <b>profissional afiliado</b> (funcionario) tambem pode
 * alterar o open-status. Um profissional de <b>outra</b> loja (nao afiliado) recebe
 * <b>403 ESTABLISHMENT_ACCESS_DENIED</b> — checagem de vinculo no serviço.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenStatusAccessIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;

    private final Faker faker = new Faker();

    String establishmentId;
    String employeeToken;   // profissional AFILIADO a esta loja (nao é o dono)
    String strangerToken;   // profissional de OUTRA loja (nao afiliado a esta)

    @BeforeAll
    void setup() throws Exception {
        // Loja 1 + dono
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
        this.establishmentId = estab.id();

        // Funcionario: usuario separado, so ROLE_PROFESSIONAL, afiliado a ESTA loja
        var employee = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, employee.token(), establishmentId, "Funcionario");
        this.employeeToken = employee.token();

        // Estranho: profissional de OUTRA loja (nao afiliado a esta)
        var stranger = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var otherEstab = TestUserUtils.becomeOwner(mvc, mapper, faker, stranger.token());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, stranger.token(), otherEstab.id(), "Estranho");
        this.strangerToken = stranger.token();

        // Deixa a loja ABERTA direto no banco (evita os gates de abrir).
        Establishment e = establishmentRepository.findById(UUID.fromString(establishmentId)).orElseThrow();
        e.setOpenStatus(OpenStatus.OPEN);
        establishmentRepository.save(e);

        log.info("[OpenStatusAccess] setup — estab={}", establishmentId);
    }

    @Test
    @Order(10)
    @DisplayName("PATCH open-status — 200 profissional AFILIADO fecha a loja")
    void affiliatedProfessional_canClose_200() throws Exception {
        mvc.perform(patch("/establishment/{id}/open-status", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", employeeToken)
                        .content("{\"openStatus\":\"TEMPORARILY_CLOSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open_status").value("TEMPORARILY_CLOSED"));
    }

    @Test
    @Order(20)
    @DisplayName("PATCH open-status — 403 profissional de OUTRA loja (nao afiliado)")
    void strangerProfessional_forbidden_403() throws Exception {
        mvc.perform(patch("/establishment/{id}/open-status", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", strangerToken)
                        .content("{\"openStatus\":\"CLOSED\"}"))
                .andExpect(status().isForbidden());
    }
}
