package com.ospaulos.vupth.integrations.campaign;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /admin/campaigns (campanha-pelo-painel, DEC-69): so o ADMIN cria, pausa e encerra; o app ve o resultado. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminCampaignIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    private String body(String code, String visibility) {
        return String.format("{\"name\":\"Lancamento\",\"code\":%s,\"type\":\"DISCOUNT\",\"visibility\":\"%s\","
                        + "\"audience\":\"CLIENT\",\"value_amount\":5.00,\"starts_at\":\"%s\",\"ends_at\":\"%s\","
                        + "\"max_redemptions\":10}",
                code == null ? "null" : "\"" + code + "\"", visibility,
                OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusDays(30));
    }

    private String admin() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(user.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);
        return user.token();
    }

    @Test
    @DisplayName("sem ADMIN: 403 em todas as rotas; sem login: 401")
    void onlyAdmin() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(get("/admin/campaigns").header("Authorization", client.token())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/campaigns").header("Authorization", client.token())
                .contentType(MediaType.APPLICATION_JSON).content(body("X" + faker.number().digits(8), "PRIVATE")))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/admin/campaigns/" + UUID.randomUUID() + "/pause").header("Authorization", client.token()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/campaigns")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ADMIN cria (201), a publica aparece no app, pausa some, encerra; cupom repetido 409; campo errado 422")
    void lifecycle() throws Exception {
        String token = admin();
        String code = "P" + faker.number().digits(8);

        String created = mvc.perform(post("/admin/campaigns").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(code, "PUBLIC")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.redemptions").value(0))
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(created).get("id").asText();

        mvc.perform(get("/campaigns/active")).andExpect(jsonPath("$[?(@.id == '%s')]", id).exists());
        mvc.perform(post("/admin/campaigns").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(code.toLowerCase(), "PRIVATE")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_CODE_TAKEN"));
        mvc.perform(post("/admin/campaigns").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(null, "PRIVATE")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_INVALID"));
        mvc.perform(post("/admin/campaigns").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(patch("/admin/campaigns/" + id + "/pause").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));
        mvc.perform(get("/campaigns/active")).andExpect(jsonPath("$[?(@.id == '%s')]", id).doesNotExist());
        mvc.perform(patch("/admin/campaigns/" + id + "/resume").header("Authorization", token))
                .andExpect(jsonPath("$.status").value("RUNNING"));
        mvc.perform(put("/admin/campaigns/" + id).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(code, "PUBLIC").replace("Lancamento", "Lancamento 2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Lancamento 2"));
        mvc.perform(patch("/admin/campaigns/" + id + "/end").header("Authorization", token))
                .andExpect(jsonPath("$.status").value("ENDED"));

        JsonNode list = mapper.readTree(mvc.perform(get("/admin/campaigns").header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(list.findValuesAsText("id")).contains(id);
        mvc.perform(patch("/admin/campaigns/" + UUID.randomUUID() + "/end").header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_NOT_FOUND"));
    }
}
