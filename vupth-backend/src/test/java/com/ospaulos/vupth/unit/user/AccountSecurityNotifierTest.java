package com.ospaulos.vupth.unit.user;

import com.ospaulos.vupth.infrastructure.mail.AccountSecurityNotifier;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** FRAQUEZAS F1 (03/11): o aviso de troca de senha e de e-mail ao dono, sem token nem e-mail novo inteiro, e sem derrubar a troca. */
@DisplayName("F1: aviso ao dono a cada troca de senha ou de e-mail")
class AccountSecurityNotifierTest {

    private final EmailSender sender = mock(EmailSender.class);
    private final AccountSecurityNotifier notifier = new AccountSecurityNotifier(sender);

    @Test
    @DisplayName("troca de e-mail: o aviso vai ao e-mail ANTIGO, com o novo mascarado")
    void emailChangedGoesToOldAddressMasked() {
        notifier.emailChanged("antigo@vupth.test", "novo.endereco@vupth.test");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sender).send(org.mockito.ArgumentMatchers.eq("antigo@vupth.test"), anyString(), body.capture());
        assertThat(body.getValue()).doesNotContain("novo.endereco@vupth.test").contains("Esqueci a senha");
    }

    @Test
    @DisplayName("troca de senha: avisa o proprio e-mail")
    void passwordChanged() {
        notifier.passwordChanged("dono@vupth.test");
        verify(sender).send(org.mockito.ArgumentMatchers.eq("dono@vupth.test"), anyString(), anyString());
    }

    @Test
    @DisplayName("falha do e-mail nao derruba a troca; sem destino nao envia")
    void bestEffort() {
        doThrow(new RuntimeException("brevo fora")).when(sender).send(anyString(), anyString(), anyString());
        assertThatCode(() -> notifier.passwordChanged("dono@vupth.test")).doesNotThrowAnyException();
        notifier.passwordChanged(" ");
        verify(sender, never()).send(org.mockito.ArgumentMatchers.eq(" "), anyString(), anyString());
    }
}
