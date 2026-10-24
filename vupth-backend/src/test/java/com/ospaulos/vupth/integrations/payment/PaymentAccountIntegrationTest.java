package com.ospaulos.vupth.integrations.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** gateway-de-pagamento, fatia 1: a conta de recebimento do profissional (aceite obrigatorio, so PROFISSIONAL ou DONO, 404 sem conta). */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ASAAS_API_KEY="})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentAccountIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private final Faker faker = new Faker();

    @Test
    @DisplayName("cliente: 403; sem login: 401; profissional sem aceite: 422; sem conta aberta: 404; sem a chave do Asaas: 503 e nada fica gravado")
    void rules() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(post("/professional/me/payment-account").header("Authorization", client.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accept\":true,\"accept_version\":\"v1\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/professional/me/payment-account")).andExpect(status().isUnauthorized());

        var pro = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, pro.token()).id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, pro.token(), establishmentId, "Beto");

        mvc.perform(get("/professional/me/payment-account").header("Authorization", pro.token()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PAYMENT_ACCOUNT_NOT_FOUND"));
        mvc.perform(post("/professional/me/payment-account").header("Authorization", pro.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accept\":false,\"accept_version\":\"v1\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PAYMENT_ACCOUNT_ACCEPT_REQUIRED"));
        mvc.perform(post("/professional/me/payment-account").header("Authorization", pro.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accept\":true}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/professional/me/payment-account").header("Authorization", pro.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accept\":true,\"accept_version\":\"v1\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ASAAS_NOT_CONFIGURED"));
        mvc.perform(get("/professional/me/payment-account").header("Authorization", pro.token()))
                .andExpect(status().isNotFound());
    }
}
