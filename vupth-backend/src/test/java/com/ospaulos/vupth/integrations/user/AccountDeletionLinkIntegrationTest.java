package com.ospaulos.vupth.integrations.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.user.dto.request.auth.AccountDeletionConfirmRequest;
import com.ospaulos.vupth.api.user.dto.request.auth.AccountDeletionRequest;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — exclusao de conta pela web (exclusao-por-link, 08/10).
 *
 * <p>{@code POST /auth/account-deletion/request} manda o link de uso unico (o {@link EmailSender} e
 * mockado para capturar o token); {@code GET /auth/account-deletion/{token}} mostra o e-mail mascarado;
 * {@code POST /auth/account-deletion/confirm} confere a senha e exclui pelo mesmo caso de uso do app.</p>
 */
@Slf4j
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountDeletionLinkIntegrationTest {

    private static final Pattern TOKEN = Pattern.compile("\\?token=([A-Za-z0-9_-]+)");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @MockBean EmailSender emailSender;

    private final Faker faker = new Faker();

    private void request(String email) throws Exception {
        mvc.perform(post("/auth/account-deletion/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new AccountDeletionRequest(email))))
                .andExpect(status().isAccepted());
    }

    /** Pede o link e devolve o token tirado do corpo do e-mail capturado. */
    private String requestAndCaptureToken(String email) throws Exception {
        clearInvocations(emailSender);
        request(email);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq(email), anyString(), body.capture());
        Matcher m = TOKEN.matcher(body.getValue());
        assertThat(m.find()).as("o e-mail leva o link com o token").isTrue();
        assertThat(body.getValue()).contains("/excluir-conta?token=");
        return m.group(1);
    }

    private org.springframework.test.web.servlet.ResultActions confirm(String token, String password) throws Exception {
        return mvc.perform(post("/auth/account-deletion/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new AccountDeletionConfirmRequest(token, password))));
    }

    @Test
    @DisplayName("e-mail sem conta -> 202 e nenhum e-mail (nao revela quem tem conta)")
    void unknownEmail_202_noSend() throws Exception {
        request("naoexiste-" + faker.number().digits(8) + "@vupth.test");
        verifyNoInteractions(emailSender);
    }

    @Test
    @DisplayName("ciclo feliz: link -> GET mascarado -> senha -> 204, o login cai e o link nao serve de novo")
    void happyPath_deletesAccount() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String token = requestAndCaptureToken(user.email());

        String prefix = user.email().substring(0, 2);
        mvc.perform(get("/auth/account-deletion/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(org.hamcrest.Matchers.startsWith(prefix + "***@")))
                .andExpect(jsonPath("$.expiresAt").exists());

        confirm(token, user.password()).andExpect(status().isNoContent());

        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(user.email(), user.password()))))
                .andExpect(status().is4xxClientError());

        confirm(token, user.password())
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DELETION_LINK_INVALID"));
    }

    @Test
    @DisplayName("senha errada -> 401 e o link segue valendo; na 5a errada o link morre (410)")
    void wrongPassword_keepsLinkUntilFifth() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String token = requestAndCaptureToken(user.email());

        for (int i = 0; i < 4; i++) {
            confirm(token, "errada-" + i)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INVALID"));
        }
        mvc.perform(get("/auth/account-deletion/{token}", token)).andExpect(status().isOk());

        confirm(token, "errada-5").andExpect(status().isUnauthorized());
        confirm(token, user.password()).andExpect(status().isGone());
    }

    @Test
    @DisplayName("pedido novo invalida o link anterior")
    void newRequest_invalidatesOldLink() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String first = requestAndCaptureToken(user.email());
        String second = requestAndCaptureToken(user.email());

        mvc.perform(get("/auth/account-deletion/{token}", first)).andExpect(status().isGone());
        mvc.perform(get("/auth/account-deletion/{token}", second)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("token que nao existe -> 410 no GET e no confirm")
    void unknownToken_410() throws Exception {
        mvc.perform(get("/auth/account-deletion/{token}", "nao-existe"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DELETION_LINK_INVALID"));
        confirm("nao-existe", "qualquer").andExpect(status().isGone());
    }

    @Test
    @DisplayName("validacao: e-mail invalido -> 422; confirm sem senha -> 422")
    void validation_422() throws Exception {
        mvc.perform(post("/auth/account-deletion/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nao-e-email\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/auth/account-deletion/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
