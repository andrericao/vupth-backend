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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — filtro de open-status na listagem publica (BUG5).
 *
 * Regra: <b>GET /establishment</b> por padrao devolve apenas lojas <b>OPEN</b>.
 * O parametro <b>?includeAll=true</b> devolve todas as ativas (inclui CLOSED/TEMPORARILY_CLOSED).
 * NAO muda o open-status persistido nem mexe na fila — so filtra a leitura.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenStatusListingFilterIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;

    private final Faker faker = new Faker();

    String openId;    // loja ABERTA (OPEN)
    String closedId;  // loja FECHADA (CLOSED — estado default ao nascer)

    @BeforeAll
    void setup() throws Exception {
        // Loja 1 — deixada ABERTA direto no banco
        var owner1 = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var open = TestUserUtils.becomeOwner(mvc, mapper, faker, owner1.token());
        this.openId = open.id();
        Establishment e = establishmentRepository.findById(UUID.fromString(openId)).orElseThrow();
        e.setOpenStatus(OpenStatus.OPEN);
        establishmentRepository.save(e);

        // Loja 2 — nasce CLOSED (default) e assim permanece
        var owner2 = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var closed = TestUserUtils.becomeOwner(mvc, mapper, faker, owner2.token());
        this.closedId = closed.id();

        log.info("[OpenStatusListingFilter] setup — open={} closed={}", openId, closedId);
    }

    @Test
    @Order(10)
    @DisplayName("GET /establishment — default so devolve loja OPEN (CLOSED some)")
    void defaultListing_onlyOpen() throws Exception {
        mvc.perform(get("/establishment"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(openId)))
                .andExpect(content().string(not(containsString(closedId))));
    }

    @Test
    @Order(20)
    @DisplayName("GET /establishment?includeAll=true — devolve OPEN e CLOSED")
    void includeAll_returnsBoth() throws Exception {
        mvc.perform(get("/establishment").param("includeAll", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(openId)))
                .andExpect(content().string(containsString(closedId)));
    }

    @Test
    @Order(30)
    @DisplayName("GET /establishment?includeAll=false — igual ao default (so OPEN)")
    void includeAllFalse_onlyOpen() throws Exception {
        mvc.perform(get("/establishment").param("includeAll", "false"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(openId)))
                .andExpect(content().string(not(containsString(closedId))));
    }
}
