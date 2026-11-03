package com.ospaulos.vupth.integrations.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DEC-179 com a chave desligada (padrao). */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("chave desligada (padrao)")
class WebCookieAuthDisabledIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    private final Faker faker = new Faker();

    @Test
    @DisplayName("o modo cookie e ignorado, as rotas da web sao 404 e o cookie nao autentica")
    void ignored() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        MvcResult r = mvc.perform(post("/auth/login").header("X-Auth-Mode", "cookie").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(user.email(), user.password()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tokens.access_token").isNotEmpty()).andReturn();
        assertThat(r.getResponse().getHeaders("Set-Cookie")).isEmpty();
        JsonNode body = mapper.readTree(r.getResponse().getContentAsString());
        String access = body.get("tokens").get("access_token").asText();

        mvc.perform(post("/auth/web/refresh")).andExpect(status().isNotFound());
        mvc.perform(post("/auth/web/logout")).andExpect(status().isNotFound());
        mvc.perform(get("/clients/me/pending-penalties").cookie(new Cookie("vupth_at", access)))
                .andExpect(status().isUnauthorized());
    }
}
