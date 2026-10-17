package com.ospaulos.vupth.integrations.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.api.user.dto.request.profile.PasswordRequest;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** senha-limite-claro: o limite de 6 a 64 caracteres (e 72 bytes) no cadastro, na troca e no login, com o 422 que o app mostra. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordLimitIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private final Faker faker = new Faker();

    private String json(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    @Test
    @DisplayName("cadastro: 65 caracteres → 422 PASSWORD_TOO_LONG; 54 caracteres cadastra e entra; login com 80 → 401")
    void registerAndLogin() throws Exception {
        String email = faker.internet().emailAddress();
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserRegisterRequest(email, "a".repeat(65)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_LONG"))
                .andExpect(jsonPath("$.min_length").value(6))
                .andExpect(jsonPath("$.max_length").value(64));
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserRegisterRequest(email, "é".repeat(37)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_LONG"));

        String longPassword = "Senha-longa-".repeat(4) + "123456";
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserRegisterRequest(email, longPassword))))
                .andExpect(status().isCreated());
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest(email, longPassword))))
                .andExpect(status().isOk());
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest(email, "a".repeat(80)))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @Test
    @DisplayName("troca de senha: longa demais → 422 PASSWORD_TOO_LONG; curta → 422 PASSWORD_TOO_SHORT")
    void updatePassword() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(patch("/profile/update-password/me").header("Authorization", user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PasswordRequest(user.password(), "a".repeat(65)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_LONG"));
        mvc.perform(patch("/profile/update-password/me").header("Authorization", user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PasswordRequest(user.password(), "abc"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_SHORT"));
    }
}
