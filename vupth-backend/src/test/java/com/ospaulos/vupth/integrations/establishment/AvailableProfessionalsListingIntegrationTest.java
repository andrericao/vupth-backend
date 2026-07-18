package com.ospaulos.vupth.integrations.establishment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — contagem de profissionais na listagem publica (Client Home, item #2).
 *
 * Regra: <b>GET /establishment</b> expoe <b>available_professionals</b> = quantos profissionais
 * afiliados COM ao menos um servico a loja tem (mesmo conjunto que alimenta estimated_wait_minutes,
 * via professionals-with-queue, que ja filtra profissional sem servico — #31). O front-client usa
 * pra decidir mostrar uma loja FECHADA na Home (so as com available_professionals > 0). Independe
 * do profissional estar ocupado agora.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AvailableProfessionalsListingIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;

    private final Faker faker = new Faker();

    String withServiceId;    // loja OPEN, 1 profissional COM servico  -> available_professionals == 1
    String withoutServiceId; // loja OPEN, 1 profissional SEM servico   -> available_professionals == 0

    @BeforeAll
    void setup() throws Exception {
        // Loja 1 — owner vira profissional afiliado E cadastra um servico.
        var owner1 = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var estab1 = TestUserUtils.becomeOwner(mvc, mapper, faker, owner1.token());
        this.withServiceId = estab1.id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, owner1.token(), withServiceId, "Prof Com Servico");
        TestUserUtils.createServiceItem(mvc, mapper, withServiceId, owner1.token(),
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        forceOpen(withServiceId);

        // Loja 2 — owner vira profissional afiliado mas NAO cadastra servico.
        var owner2 = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var estab2 = TestUserUtils.becomeOwner(mvc, mapper, faker, owner2.token());
        this.withoutServiceId = estab2.id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, owner2.token(), withoutServiceId, "Prof Sem Servico");
        forceOpen(withoutServiceId);

        log.info("[AvailableProfessionals] setup — withService={} withoutService={}",
                withServiceId, withoutServiceId);
    }

    /** Abre a loja direto no banco (pula o gate #31, que exigiria profissional com servico). */
    private void forceOpen(String establishmentId) {
        Establishment e = establishmentRepository.findById(UUID.fromString(establishmentId)).orElseThrow();
        e.setOpenStatus(OpenStatus.OPEN);
        establishmentRepository.save(e);
    }

    @Test
    @Order(10)
    @DisplayName("GET /establishment — loja com 1 profissional-com-servico expoe available_professionals == 1")
    void listing_withServicedProfessional_countsOne() throws Exception {
        assertEquals(1, availableProfessionalsOf(withServiceId));
    }

    @Test
    @Order(20)
    @DisplayName("GET /establishment — profissional SEM servico nao conta: available_professionals == 0")
    void listing_professionalWithoutService_countsZero() throws Exception {
        assertEquals(0, availableProfessionalsOf(withoutServiceId));
    }

    /** Le a listagem publica e devolve o available_professionals da loja pedida. */
    private int availableProfessionalsOf(String establishmentId) throws Exception {
        String body = mvc.perform(get("/establishment"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> list = mapper.readValue(body, new TypeReference<>() {});
        Map<String, Object> mine = list.stream()
                .filter(m -> establishmentId.equals(m.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("loja " + establishmentId + " nao veio na listagem"));

        Object value = mine.get("available_professionals");
        assertNotNull(value, "available_professionals ausente no payload da listagem");
        return ((Number) value).intValue();
    }
}
