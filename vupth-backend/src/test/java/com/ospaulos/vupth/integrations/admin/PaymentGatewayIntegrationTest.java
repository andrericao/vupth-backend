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

/** asaas-sandbox-no-fluxo (DEC-119): o estado da conexao e publico; so o ADMIN liga; sem chave nao liga; sem conexao vale o fluxo de sempre. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ASAAS_API_KEY="})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentGatewayIntegrationTest {

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
    @DisplayName("GET /payment-gateway e publico e diz desconectado por padrao (sem modo)")
    void publicStateDefaultsToDisconnected() throws Exception {
        mvc.perform(get("/payment-gateway"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(jsonPath("$.mode").doesNotExist());
    }

    @Test
    @DisplayName("so o ADMIN le e muda a conexao; sem a chave nao liga (422 ASAAS_NOT_CONFIGURED); desligar sempre pode")
    void adminSwitch() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(put("/admin/settings/asaas-connection").header("Authorization", client.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"connected\":true}"))
                .andExpect(status().isForbidden());

        String admin = adminToken();
        mvc.perform(get("/admin/settings/asaas-connection").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(jsonPath("$.configured").value(false));
        mvc.perform(put("/admin/settings/asaas-connection").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"connected\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ASAAS_NOT_CONFIGURED"));
        mvc.perform(put("/admin/settings/asaas-connection").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"connected\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false));
    }

    @Test
    @DisplayName("sem conexao o pagamento do atendimento segue o fluxo de sempre: a resposta nao traz gateway")
    void paymentWithoutGateway() throws Exception {
        var pro = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, pro.token()).id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, pro.token(), establishmentId, "Beto");
        mvc.perform(get("/professional/me/pending-payments").header("Authorization", pro.token()))
                .andExpect(status().isOk());
    }
}
