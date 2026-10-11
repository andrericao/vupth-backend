package com.ospaulos.vupth.infrastructure.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Unit tests — o envio por SMTP (cobertura-98): monta a mensagem, pula dominio de teste e traduz a falha. */
@ExtendWith(OutputCaptureExtension.class)
class SmtpEmailSenderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final SmtpEmailSender sender = new SmtpEmailSender(mailSender, "no-reply@vupth.app", "smtp.test", 587);

    @Test
    @DisplayName("Reset de senha sai do remetente configurado, com o assunto e o codigo no corpo")
    void sendsPasswordResetCode() {
        sender.sendPasswordResetCode("ana@vupth.com", "482913");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getFrom()).isEqualTo("no-reply@vupth.app");
        assertThat(message.getTo()).containsExactly("ana@vupth.com");
        assertThat(message.getSubject()).isEqualTo(PasswordResetMessage.SUBJECT);
        assertThat(message.getText()).contains("482913");
    }

    @Test
    @DisplayName("Dominio reservado de teste: nada sai pelo SMTP")
    void skipsReservedDomain() {
        sender.send("cliente@vupth.test", "Assunto", "Corpo");

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("Falha do SMTP vira EmailDeliveryException")
    void wrapsMailFailure() {
        doThrow(new MailSendException("connection refused")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> sender.send("ana@vupth.com", "Assunto", "Corpo"))
                .isInstanceOf(EmailDeliveryException.class)
                .hasCauseInstanceOf(MailSendException.class);
    }

    @Test
    @DisplayName("Boot anuncia o host; host vazio ou nulo vira erro no log")
    void announcesProvider(CapturedOutput output) {
        sender.anunciaProvider();
        new SmtpEmailSender(mailSender, "x@vupth.app", " ", 587).anunciaProvider();
        new SmtpEmailSender(mailSender, "x@vupth.app", null, 587).anunciaProvider();

        assertThat(output).contains("host=smtp.test port=587").contains("spring.mail.host esta VAZIO");
    }
}
