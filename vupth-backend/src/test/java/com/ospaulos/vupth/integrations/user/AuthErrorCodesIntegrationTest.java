package com.ospaulos.vupth.integrations.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.api.user.dto.request.profile.UserRegisterRequest;
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

/**
 * Todo erro de login e cadastro traz um {@code code} estavel (17/10): os apps traduzem a mensagem pelo codigo, porque o
 * texto do servidor pode vir em ingles ("E-mail not registered"). Este teste trava os codigos.
 */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthErrorCodesIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private final Faker faker = new Faker();

    private String login(String email, String password) throws Exception {
        return mapper.writeValueAsString(new LoginRequest(email, password));
    }

    private void postJson(String url, String body, int status, String code) throws Exception {
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code));
    }

    @Test
    @DisplayName("login: e-mail sem conta, senha errada, corpo vazio ou quebrado — cada um com o seu code")
    void loginErrors() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);

        postJson("/auth/login", login("ninguem-" + faker.internet().emailAddress(), "SenhaBoa123"), 401, "EMAIL_NOT_FOUND");
        postJson("/auth/login", login(user.email(), "senha-errada-123"), 401, "BAD_CREDENTIALS");
        // o login nao valida o formato: corpo vazio ou e-mail estranho cai em "e-mail sem conta", tambem com code
        postJson("/auth/login", "{}", 401, "EMAIL_NOT_FOUND");
        postJson("/auth/login", "{\"email\":\"nao-e-email\",\"password\":\"x\"}", 401, "EMAIL_NOT_FOUND");
        postJson("/auth/login", "isto nao e json", 400, "INVALID_REQUEST_BODY");
    }

    @Test
    @DisplayName("cadastro: e-mail repetido, e-mail invalido e senha fora da regra — cada um com o seu code")
    void registerErrors() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String fresh = faker.internet().emailAddress();

        postJson("/auth/register", mapper.writeValueAsString(new UserRegisterRequest(user.email(), "SenhaBoa123")),
                409, "EMAIL_NOT_AVAILABLE");
        postJson("/auth/register", mapper.writeValueAsString(new UserRegisterRequest(fresh, "abc")),
                422, "PASSWORD_TOO_SHORT");
        postJson("/auth/register", mapper.writeValueAsString(new UserRegisterRequest(fresh, "a".repeat(65))),
                422, "PASSWORD_TOO_LONG");
        postJson("/auth/register", "{\"email\":\"nao-e-email\",\"password\":\"SenhaBoa123\"}", 422, "VALIDATION_ERROR");
        postJson("/auth/register", "{}", 422, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("sem token numa rota protegida e com token invalido: 401 (sem corpo; o app trata pelo status e renova a sessao)")
    void unauthenticated() throws Exception {
        mvc.perform(get("/clients/me/pending-penalties")).andExpect(status().isUnauthorized());
        mvc.perform(get("/clients/me/pending-penalties").header("Authorization", "Bearer token.invalido.aqui"))
                .andExpect(status().isUnauthorized());
    }
}
