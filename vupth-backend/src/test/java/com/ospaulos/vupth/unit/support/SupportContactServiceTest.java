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
        return new SupportContactRequest("Ana", "ana@exemplo.com", "parceiro", "RECLAMACAO", assunto, "Nao consigo abrir a fila.");
    }

    @Test
    @DisplayName("encaminha ao e-mail configurado, com quem responder no corpo")
    void forwardsToInbox() {
        new SupportContactService(emailSender, " suporte@vupth.test ").forward(request("Fila"));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq("suporte@vupth.test"), subject.capture(), body.capture());
        assertThat(subject.getValue()).isEqualTo("[VUPTH · Parceiro · Reclamação] Fila");
        assertThat(body.getValue()).contains("Nome: Ana", "Responder para: ana@exemplo.com", "Perfil: Parceiro",
                "Categoria: Reclamação", "Assunto: Fila", "Nao consigo abrir a fila.");
    }

    @Test
    @DisplayName("quebra de linha no assunto nao vira cabecalho novo")
    void subjectInOneLine() {
        new SupportContactService(emailSender, "suporte@vupth.test").forward(request("Fila\r\nBcc: x@y.z"));

        verify(emailSender).send(anyString(), eq("[VUPTH · Parceiro · Reclamação] Fila Bcc: x@y.z"), anyString());
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
