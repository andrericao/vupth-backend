package com.ospaulos.vupth.integrations.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ledger-por-conta: as contas com a tarifa de Pix, os totais por conta e o consolidado; so ADMIN com FINANCE. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ASAAS_API_KEY="})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FinanceAccountsIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    private String adminToken() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(user.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);
        return user.token();
    }

    @Test
    @DisplayName("as contas nascem com a tarifa de Pix (Asaas 1,99; PJ 0,00); o ADMIN muda a tarifa; os totais respondem por conta e juntos")
    void accountsAndTotals() throws Exception {
        String admin = adminToken();
        mvc.perform(get("/admin/finance/accounts").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == 'ASAAS')].pix_fee").value(1.99))
                .andExpect(jsonPath("$[?(@.key == 'PJ')].pix_fee").value(0.0))
                .andExpect(jsonPath("$[?(@.key == 'ASAAS')].connected").value(false));

        mvc.perform(put("/admin/finance/accounts/PJ").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pix_fee\":0.50}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pix_fee").value(0.5));
        mvc.perform(put("/admin/finance/accounts/PJ").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pix_fee\":0.00}"))
                .andExpect(status().isOk());
        mvc.perform(put("/admin/finance/accounts/PJ").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pix_fee\":-1}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/admin/finance/accounts/NADA").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pix_fee\":1.00}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FINANCE_ACCOUNT_NOT_FOUND"));

        mvc.perform(get("/admin/finance/totals").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account").value("all"))
                .andExpect(jsonPath("$.totals.entries").isNumber())
                .andExpect(jsonPath("$.totals.balance").isNumber())
                .andExpect(jsonPath("$.by_account.length()").value(2));
        mvc.perform(get("/admin/finance/totals").param("account", "asaas").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account").value("ASAAS"))
                .andExpect(jsonPath("$.totals.fees").isNumber())
                .andExpect(jsonPath("$.by_account").doesNotExist());
        mvc.perform(get("/admin/finance/totals").param("account", "banco-x").header("Authorization", admin))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("quem nao e ADMIN recebe 403; sem login, 401")
    void onlyAdmin() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(get("/admin/finance/accounts").header("Authorization", client.token())).andExpect(status().isForbidden());
        mvc.perform(get("/admin/finance/totals").header("Authorization", client.token())).andExpect(status().isForbidden());
        mvc.perform(put("/admin/finance/accounts/PJ").header("Authorization", client.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pix_fee\":1.00}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/finance/accounts")).andExpect(status().isUnauthorized());
    }
}
