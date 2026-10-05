package com.ospaulos.vupth.integrations.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.user.dto.request.auth.ForgotPasswordRequest;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.api.user.dto.request.auth.ResetPasswordRequest;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — "esqueci minha senha" (backlog #1, fatia 1).
 *
 * <p>{@code POST /auth/forgot-password} gera um código de 6 dígitos (guardado só como hash) e o
 * envia por e-mail; {@code POST /auth/reset-password} consome o código e troca a senha. O
 * {@link EmailSender} é mockado ({@code @MockBean}) para capturar o código gerado — assim o teste
 * exercita o ciclo completo sem SMTP real (em produção/homolog o provider é {@code none}).
 *
 * <p>Cobre: e-mail inexistente → 404 {@code EMAIL_NOT_FOUND}, sem envio (D2, BAK-6); envio do código; ciclo feliz
 * (reset → login com a senha nova funciona, senha antiga falha, código não pode ser reusado);
 * código errado → 400; e-mail desconhecido no reset → 400; validação de campos → 422.
 */
@Slf4j
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ForgotPasswordIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @MockBean EmailSender emailSender;

    private final Faker faker = new Faker();

    private void forgot(String email) throws Exception {
        mvc.perform(post("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ForgotPasswordRequest(email))))
                .andExpect(status().isOk());
    }

    /** Dispara o forgot e devolve o código de 6 dígitos capturado do EmailSender. */
    private String forgotAndCaptureCode(String email) throws Exception {
        forgot(email);
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendPasswordResetCode(eq(email), codeCaptor.capture());
        return codeCaptor.getValue();
    }

    @Test
    @DisplayName("forgot-password — e-mail inexistente responde 404 EMAIL_NOT_FOUND e NÃO envia (D2)")
    void forgotPassword_unknownEmail_404_noSend() throws Exception {
        mvc.perform(post("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new ForgotPasswordRequest("naoexiste-" + faker.number().digits(8) + "@vupth.test"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_FOUND"));
        verifyNoInteractions(emailSender);
    }

    @Test
    @DisplayName("forgot-password — e-mail existente responde 200 e dispara o código por e-mail")
    void forgotPassword_knownEmail_200_sendsCode() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);

        String code = forgotAndCaptureCode(user.email());
        org.assertj.core.api.Assertions.assertThat(code).matches("\\d{6}");
    }

    @Test
    @DisplayName("reset-password — ciclo feliz: troca a senha, login novo funciona, antigo falha, código não reusa")
    void resetPassword_happyPath() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String newPassword = "NovaSenha@789";

        String code = forgotAndCaptureCode(user.email());

        // Reset com o código correto → 200.
        mvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new ResetPasswordRequest(user.email(), code, newPassword))))
                .andExpect(status().isOk());

        // Login com a senha NOVA → 200.
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(user.email(), newPassword))))
                .andExpect(status().isOk());

        // Login com a senha ANTIGA → 401.
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(user.email(), user.password()))))
                .andExpect(status().isUnauthorized());

        // Reusar o mesmo código → 400 (uso único, já consumido).
        mvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new ResetPasswordRequest(user.email(), code, "OutraSenha@1"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_CODE_INVALID"));
    }

    @Test
    @DisplayName("reset-password — código errado → 400 RESET_CODE_INVALID (senha não muda)")
    void resetPassword_wrongCode_400() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        forgotAndCaptureCode(user.email()); // gera um código válido, mas mandamos outro

        mvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new ResetPasswordRequest(user.email(), "000000", "NovaSenha@789"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_CODE_INVALID"));

        // A senha original continua válida.
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(user.email(), user.password()))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("reset-password — e-mail desconhecido → 400 RESET_CODE_INVALID (sem vazar existência)")
    void resetPassword_unknownEmail_400() throws Exception {
        mvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ResetPasswordRequest(
                                "naoexiste-" + faker.number().digits(8) + "@vupth.test",
                                "123456", "NovaSenha@789"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_CODE_INVALID"));
    }

    @Test
    @DisplayName("reset-password — campos em branco → 422 VALIDATION_ERROR")
    void resetPassword_blankFields_422() throws Exception {
        mvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ResetPasswordRequest("", "", ""))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("forgot-password — e-mail em branco → 422 VALIDATION_ERROR")
    void forgotPassword_blankEmail_422() throws Exception {
        mvc.perform(post("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ForgotPasswordRequest(""))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
