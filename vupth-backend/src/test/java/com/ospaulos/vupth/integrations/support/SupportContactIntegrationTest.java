package com.ospaulos.vupth.integrations.support;

import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /support/contact (formulario-do-suporte, DEC-59): publico, 204 com a mensagem enviada, 422 com campo ruim. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "vupth.support.inbox-email=suporte@vupth.test")
class SupportContactIntegrationTest {

    @Autowired MockMvc mvc;
    @MockBean EmailSender emailSender;

    private static String body(String perfil, String email, String mensagem) {
        return String.format("{\"nome\":\"Ana\",\"email\":\"%s\",\"perfil\":\"%s\",\"assunto\":\"Fila\",\"mensagem\":\"%s\"}",
                email, perfil, mensagem);
    }

    @Test
    @DisplayName("sem token: 204 e o e-mail sai para a caixa do suporte")
    void publicAndSent() throws Exception {
        mvc.perform(post("/support/contact").contentType(MediaType.APPLICATION_JSON)
                        .content(body("cliente", "ana@exemplo.com", "Oi")))
                .andExpect(status().isNoContent());

        verify(emailSender).send(eq("suporte@vupth.test"), contains("cliente"), contains("ana@exemplo.com"));
    }

    @Test
    @DisplayName("perfil fora da lista, e-mail invalido ou mensagem acima de 2000: 422 e nada sai")
    void invalid() throws Exception {
        mvc.perform(post("/support/contact").contentType(MediaType.APPLICATION_JSON)
                        .content(body("dono", "ana@exemplo.com", "Oi")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/support/contact").contentType(MediaType.APPLICATION_JSON)
                        .content(body("cliente", "nao-e-email", "Oi")))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/support/contact").contentType(MediaType.APPLICATION_JSON)
                        .content(body("cliente", "ana@exemplo.com", "x".repeat(2001))))
                .andExpect(status().isUnprocessableEntity());

        verifyNoInteractions(emailSender);
    }
}
