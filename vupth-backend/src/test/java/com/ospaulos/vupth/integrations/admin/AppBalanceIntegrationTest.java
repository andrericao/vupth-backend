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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** saldo-do-app-e-bonificacao (DEC-120): leitura so do ADMIN; sem o Asaas ligado, so o devedor. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ASAAS_API_KEY="})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppBalanceIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    @Test
    @DisplayName("ADMIN le o saldo (sem Asaas: so o devedor); quem nao e ADMIN recebe 403")
    void readOnlyForAdmin() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(get("/admin/finance/app-balance").header("Authorization", client.token()))
                .andExpect(status().isForbidden());

        var admin = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(admin.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);

        mvc.perform(get("/admin/finance/app-balance").header("Authorization", admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owed_total").isNumber())
                .andExpect(jsonPath("$.pending_release_total").isNumber())
                .andExpect(jsonPath("$.beneficiaries").isArray())
                .andExpect(jsonPath("$.asaas_connected").value(false))
                .andExpect(jsonPath("$.app_balance").doesNotExist())
                .andExpect(jsonPath("$.deposit_needed").doesNotExist());
    }
}
