package com.ospaulos.vupth.integrations.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
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

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /feedback (feedback-por-email, DEC-167): so logado; 204, 422 e 429 pelo teto do dia. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"vupth.feedback.inbox-email=caixa@vupth.test", "vupth.feedback.daily-limit-per-user=2"})
class FeedbackIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockBean EmailSender emailSender;

    private final Faker faker = new Faker();

    private static final String OK = "{\"text\":\"Ficou otimo\",\"category\":\"ELOGIO\",\"app\":\"CLIENT\","
            + "\"app_version\":\"2.0.1\",\"device\":\"Moto G\"}";

    @Test
    @DisplayName("sem login: nao entra e nada sai")
    void needsLogin() throws Exception {
        mvc.perform(post("/feedback").contentType(MediaType.APPLICATION_JSON).content(OK))
                .andExpect(status().is4xxClientError());
        verifyNoInteractions(emailSender);
    }

    @Test
    @DisplayName("logado: 204 e o e-mail sai com o titulo e o e-mail de quem responder; 422 com app ou texto ruim; 429 acima do teto")
    void sendsValidatesAndLimits() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);

        mvc.perform(post("/feedback").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", user.token()).content("{\"text\":\"x\",\"app\":\"ADMIN\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/feedback").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", user.token()).content("{\"text\":\"\",\"app\":\"CLIENT\"}"))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(emailSender);

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/feedback").contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", user.token()).content(OK))
                    .andExpect(status().isNoContent());
        }
        verify(emailSender, times(2)).send(eq("caixa@vupth.test"), eq("[VUPTH · Client · 2.0.1] feedback"),
                contains(user.email()));

        mvc.perform(post("/feedback").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", user.token()).content(OK))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("FEEDBACK_LIMIT"));
    }
}
