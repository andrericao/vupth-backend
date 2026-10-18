package com.ospaulos.vupth.unit.support;

import com.ospaulos.vupth.api.support.dto.request.SupportContactRequest;
import com.ospaulos.vupth.domain.support.service.SupportContactService;
import com.ospaulos.vupth.infrastructure.mail.EmailDeliveryException;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** formulario-do-suporte (DEC-59): a mensagem vai ao e-mail da variavel, numa linha de assunto so. */
class SupportContactServiceTest {

    private final EmailSender emailSender = mock(EmailSender.class);

    private static SupportContactRequest request(String assunto) {
        return request("profissional", assunto);
    }

    private static SupportContactRequest request(String perfil, String assunto) {
        return new SupportContactRequest("Ana", "ana@exemplo.com", perfil, "RECLAMACAO", assunto, "Nao consigo abrir a fila.");
    }

    @Test
    @DisplayName("encaminha ao e-mail configurado, com quem responder no corpo")
    void forwardsToInbox() {
        new SupportContactService(emailSender, " suporte@vupth.test ").forward(request("Fila"));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq("suporte@vupth.test"), subject.capture(), body.capture());
        assertThat(subject.getValue()).isEqualTo("[VUPTH · Profissional · Reclamação] Fila");
        assertThat(body.getValue()).contains("Nome: Ana", "Responder para: ana@exemplo.com", "Qual o seu perfil: Profissional",
                "Categoria: Reclamação", "Assunto: Fila", "Nao consigo abrir a fila.");
    }

    @Test
    @DisplayName("categoria FEEDBACK (feedback sobre o app): aparece como Feedback no titulo")
    void feedbackCategory() {
        new SupportContactService(emailSender, "suporte@vupth.test").forward(
                new SupportContactRequest("Ana", "ana@exemplo.com", "cliente", "FEEDBACK", "Gostei", "Sugiro um modo escuro."));

        verify(emailSender).send(anyString(), eq("[VUPTH · Cliente · Feedback] Gostei"), contains("Categoria: Feedback"));
    }

    @Test
    @DisplayName("perfil do formulario: dono e o parceiro antigo aparecem com o nome certo no titulo")
    void profileLabels() {
        SupportContactService service = new SupportContactService(emailSender, "suporte@vupth.test");
        service.forward(request("dono", "Fila"));
        service.forward(request("parceiro", "Fila"));

        verify(emailSender).send(anyString(), eq("[VUPTH · Dono · Reclamação] Fila"), contains("Qual o seu perfil: Dono"));
        verify(emailSender).send(anyString(), eq("[VUPTH · Profissional ou dono · Reclamação] Fila"), anyString());
    }

    @Test
    @DisplayName("quebra de linha no assunto nao vira cabecalho novo")
    void subjectInOneLine() {
        new SupportContactService(emailSender, "suporte@vupth.test").forward(request("Fila\r\nBcc: x@y.z"));

        verify(emailSender).send(anyString(), eq("[VUPTH · Profissional · Reclamação] Fila Bcc: x@y.z"), anyString());
    }

    @Test
    @DisplayName("sem SUPPORT_INBOX_EMAIL: falha em vez de sumir com a mensagem")
    void noInbox() {
        assertThatThrownBy(() -> new SupportContactService(emailSender, "").forward(request("Fila")))
                .isInstanceOf(EmailDeliveryException.class);
        assertThatThrownBy(() -> new SupportContactService(emailSender, null).forward(request("Fila")))
                .isInstanceOf(EmailDeliveryException.class);
        verifyNoInteractions(emailSender);
    }
}
