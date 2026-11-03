package com.ospaulos.vupth.integrations.establishment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.establishment.service.DeepestShowcasePosition;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DEC-176 / F13: o maior list_position ja recebido fica no banco (sobrevive ao reinicio) e o ADMIN o le com a data. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeepestShowcasePositionIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired DeepestShowcasePosition deepest;

    private final Faker faker = new Faker();

    @Test
    @DisplayName("so o maior fica; posicao invalida e loja inexistente nao contam; o ADMIN le valor e data")
    void keepsTheMax() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String shop = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token()).id();
        int base = deepest.read().map(DeepestShowcasePosition.Deepest::position).orElse(0);

        mvc.perform(get("/establishment/id/{id}", shop).param("list_position", String.valueOf(base + 5))).andExpect(status().isOk());
        mvc.perform(get("/establishment/id/{id}", shop).param("list_position", String.valueOf(base + 3))).andExpect(status().isOk());
        mvc.perform(get("/establishment/id/{id}/professionals-with-queue", shop)
                .param("list_position", String.valueOf(base + 9))).andExpect(status().isOk());
        mvc.perform(get("/establishment/id/{id}", shop).param("list_position", "0")).andExpect(status().isOk());
        mvc.perform(get("/establishment/id/{id}", UUID.randomUUID()).param("list_position", String.valueOf(base + 500)));

        assertThat(deepest.read().orElseThrow().position()).isEqualTo(base + 9);

        var admin = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser u = userRepository.findById(UUID.fromString(admin.id())).orElseThrow();
        u.addRole(RoleType.ADMIN);
        userRepository.save(u);
        JsonNode stats = mapper.readTree(mvc.perform(get("/admin/overview/showcase").header("Authorization", admin.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(stats.get("max_opened_position").asInt()).isEqualTo(base + 9);
        assertThat(stats.get("max_opened_position_at").asText()).isNotBlank();
        assertThat(stats.get("max_items").asInt()).isEqualTo(200);

        mvc.perform(get("/admin/overview/showcase").header("Authorization", owner.token())).andExpect(status().isForbidden());
    }
}
