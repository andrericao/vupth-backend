package com.ospaulos.vupth.integrations.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.asaas.AsaasEventRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** testes-asaas-sandbox, fatia 1: o webhook (token), o relatorio do ADMIN e a cobranca com o sandbox desligado. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ASAAS_WEBHOOK_TOKEN=segredo-de-teste", "ASAAS_API_KEY="})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AsaasSandboxIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired AsaasEventRepository events;

    private final Faker faker = new Faker();

    private TestUserUtils.UserAuthAndToken admin() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(user.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);
        return user;
    }

    private static String payload(String id, String event, String payment, String value, String net) {
        return String.format("""
                {"id":"%s","event":"%s","dateCreated":"2026-10-18 10:00:00","payment":
                 {"id":"%s","value":%s,"netValue":%s,"status":"RECEIVED","billingType":"PIX","externalReference":"sem-atendimento"}}
                """, id, event, payment, value, net);
    }

    @Test
    @DisplayName("webhook: sem o token 403, token errado 403; com o token 200, e o reenvio tambem 200 sem gravar de novo")
    void webhook() throws Exception {
        String id = "evt_" + UUID.randomUUID();
        String body = payload(id, "PAYMENT_RECEIVED", "pay_" + UUID.randomUUID(), "55.90", "53.91");

        mvc.perform(post("/webhooks/asaas").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/webhooks/asaas").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("asaas-access-token", "errado"))
                .andExpect(status().isForbidden());
        assertThat(events.existsByEventId(id)).isFalse();

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/webhooks/asaas").contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("asaas-access-token", "segredo-de-teste"))
                    .andExpect(status().isOk());
        }
        assertThat(events.findAll().stream().filter(e -> id.equals(e.getEventId())).count()).isEqualTo(1);

        mvc.perform(post("/webhooks/asaas").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"x\"}")
                        .header("asaas-access-token", "segredo-de-teste"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("relatorio: o ADMIN ve a cobranca recebida pelo webhook, com o que o Asaas ficou; o cliente comum nao ve")
    void report() throws Exception {
        var owner = admin();
        String payment = "pay_" + UUID.randomUUID();
        mvc.perform(post("/webhooks/asaas").contentType(MediaType.APPLICATION_JSON)
                        .content(payload("evt_" + UUID.randomUUID(), "PAYMENT_RECEIVED", payment, "55.90", "53.91"))
                        .header("asaas-access-token", "segredo-de-teste"))
                .andExpect(status().isOk());

        mvc.perform(get("/admin/finance/asaas/sandbox/report")
                        .param("from", java.time.OffsetDateTime.now().minusHours(1).toString())
                        .param("to", java.time.OffsetDateTime.now().plusHours(1).toString())
                        .header("Authorization", owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments[?(@.payment_id == '" + payment + "')].client_paid").value(55.90))
                .andExpect(jsonPath("$.payments[?(@.payment_id == '" + payment + "')].asaas_fee_real").value(1.99))
                .andExpect(jsonPath("$.not_measured[0]").value("bonus"));

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(get("/admin/finance/asaas/sandbox/report").header("Authorization", client.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("cobranca: sem ASAAS_API_KEY o sandbox esta desligado (503); tipo invalido 422; valor abaixo do minimo 422; cliente comum 403")
    void charge() throws Exception {
        var owner = admin();

        mvc.perform(post("/admin/finance/asaas/sandbox/charges").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":55.90}").header("Authorization", owner.token()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ASAAS_SANDBOX_DISABLED"));
        mvc.perform(post("/admin/finance/asaas/sandbox/charges").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":55.90,\"billing_type\":\"cheque\"}").header("Authorization", owner.token()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ASAAS_BILLING_TYPE_INVALID"));
        mvc.perform(post("/admin/finance/asaas/sandbox/charges").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1.00}").header("Authorization", owner.token()))
                .andExpect(status().isUnprocessableEntity());

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(post("/admin/finance/asaas/sandbox/charges").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":55.90}").header("Authorization", client.token()))
                .andExpect(status().isForbidden());
    }
}
