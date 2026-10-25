package com.ospaulos.vupth.integrations.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** valor-minimo-do-servico (DEC-121): o minimo e uma flag (R$ 5,00); o cadastro avisa mas nao bloqueia. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ServiceMinPriceIntegrationTest {

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
    @DisplayName("padrao R$ 5,00; o admin muda; cadastro abaixo do minimo avisa e continua criado")
    void minimumFlagAndWarning() throws Exception {
        var pro = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, pro.token()).id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, pro.token(), establishmentId, "Beto");

        mvc.perform(get("/settings/service-min-price").header("Authorization", pro.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.min_price").value(5.0));

        mvc.perform(post(establishmentId, pro.token(), "Normal", 30))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.min_price").value(5.0))
                .andExpect(jsonPath("$.warning").doesNotExist());

        mvc.perform(post(establishmentId, pro.token(), "Barato2", 5))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warning.code").value("PRICE_BELOW_MINIMUM"));

        String admin = adminToken();
        mvc.perform(put("/admin/settings/service-min-price").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"min_price\":40.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.min_price").value(40.0));
        mvc.perform(post(establishmentId, pro.token(), "Normal2", 30))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warning.code").value("PRICE_BELOW_MINIMUM"));
        mvc.perform(put("/admin/settings/service-min-price").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"min_price\":5.00}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("so o admin muda o minimo (403 para os demais) e valor negativo → 422")
    void guards() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(put("/admin/settings/service-min-price").header("Authorization", client.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"min_price\":1.00}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/admin/settings/service-min-price").header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"min_price\":-1}"))
                .andExpect(status().isUnprocessableEntity());
    }

    private org.springframework.test.web.servlet.RequestBuilder post(String establishmentId, String token, String name, int price)
            throws Exception {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/establishment/{id}/service-items", establishmentId).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new com.ospaulos.vupth.api.serviceItem.dto.request.ServiceItemRequest(
                        UUID.fromString(establishmentId), ServiceItemType.CORTE_MAQUINA, name,
                        java.math.BigDecimal.valueOf(price), 30, null, null)));
    }
}
