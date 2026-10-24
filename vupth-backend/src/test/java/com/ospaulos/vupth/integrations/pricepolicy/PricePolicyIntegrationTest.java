package com.ospaulos.vupth.integrations.pricepolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * politica-de-preco-com-ciencia (DEC-100): sem a ciencia no prazo de 7 dias, abrir a loja e a fila e barrado (409
 * PRICE_POLICY_ACK_REQUIRED); registrar a ciencia libera. Nao publica a politica de verdade aqui (isso reajusta todos os servicos do
 * banco): grava a versao e o aviso direto, como a publicacao faria.
 */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PricePolicyIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired JdbcTemplate jdbc;

    private final Faker faker = new Faker();

    @Test
    @DisplayName("sem ciencia no prazo: nao vira WORKING, nao abre a fila e nao abre a loja (409 com versao e prazo); registrar a ciencia libera")
    void gateAndAcknowledge() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, owner.token(), estab.id(), "Dono Profissional");
        TestUserUtils.createServiceItem(mvc, mapper, estab.id(), owner.token(), ServiceItemType.CORTE_MAQUINA, "Corte",
                BigDecimal.valueOf(30));
        UUID proId = UUID.fromString(owner.id());

        Integer next = jdbc.queryForObject("SELECT coalesce(max(version), 0) + 1 FROM price_policy", Integer.class);
        int version = next == null ? 1 : next;
        jdbc.update("INSERT INTO price_policy (version, published_at, summary, services_updated, professionals_notified) "
                + "VALUES (?, now() - interval '8 days', 'Teste da politica', 1, 1)", version);
        jdbc.update("INSERT INTO professional_price_ack (professional_id, policy_version, notified_at, deadline_at) "
                + "VALUES (?, ?, now() - interval '8 days', now() - interval '1 day')", proId, version);

        mvc.perform(get("/professional/me/price-policy").header("Authorization", owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(version))
                .andExpect(jsonPath("$.acknowledged").value(false))
                .andExpect(jsonPath("$.blocked").value(true))
                .andExpect(jsonPath("$.deadline_at").isNotEmpty());

        mvc.perform(patch("/professional/me/status").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", owner.token()).content("{\"status\":\"WORKING\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_POLICY_ACK_REQUIRED"))
                .andExpect(jsonPath("$.policy_version").value(version))
                .andExpect(jsonPath("$.deadline_at").isNotEmpty());
        mvc.perform(patch("/professional/me/accepting-new").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", owner.token()).content("{\"value\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_POLICY_ACK_REQUIRED"));
        mvc.perform(patch("/establishment/{id}/open-status", estab.id()).contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", owner.token()).content("{\"openStatus\":\"OPEN\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_POLICY_ACK_REQUIRED"));

        // ciencia de versao que nao existe / de quem nao foi avisado
        mvc.perform(post("/professional/me/price-policy/ack").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", owner.token()).content("{\"version\":" + (version + 1000) + "}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRICE_POLICY_NOT_FOUND"));
        mvc.perform(post("/professional/me/price-policy/ack").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", owner.token()).content("{}"))
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(post("/professional/me/price-policy/ack").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", owner.token()).content("{\"version\":" + version + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledged").value(true))
                .andExpect(jsonPath("$.blocked").value(false))
                .andExpect(jsonPath("$.acknowledged_at").isNotEmpty());
        // de novo e idempotente
        mvc.perform(post("/professional/me/price-policy/ack").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", owner.token()).content("{\"version\":" + version + "}"))
                .andExpect(status().isOk());

        // com a ciencia, o gate deixa de barrar (outras regras de abertura podem responder outra coisa, mas nao esta)
        mvc.perform(patch("/professional/me/accepting-new").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", owner.token()).content("{\"value\":true}"))
                .andExpect(status().isOk());

        jdbc.update("DELETE FROM professional_price_ack WHERE policy_version = ?", version);
        jdbc.update("DELETE FROM price_policy WHERE version = ?", version);
    }

    @Test
    @DisplayName("admin: preview e historico so para ADMIN (403 para os outros); o preview conta o que mudaria sem mudar nada")
    void adminPreviewAndHistory() throws Exception {
        var plain = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(get("/admin/price-policy/preview").header("Authorization", plain.token()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/price-policy").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", plain.token()).content("{\"summary\":\"x\"}"))
                .andExpect(status().isForbidden());

        BaseUser base = userRepository.findById(UUID.fromString(plain.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);

        mvc.perform(get("/admin/price-policy/preview").header("Authorization", plain.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services_total").isNumber())
                .andExpect(jsonPath("$.services_to_update").isNumber())
                .andExpect(jsonPath("$.legacy_without_split").isNumber())
                .andExpect(jsonPath("$.professionals_to_notify").isNumber())
                .andExpect(jsonPath("$.examples").isArray());
        mvc.perform(get("/admin/price-policy").header("Authorization", plain.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mvc.perform(post("/admin/price-policy").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", plain.token()).content("{\"summary\":\"\"}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
