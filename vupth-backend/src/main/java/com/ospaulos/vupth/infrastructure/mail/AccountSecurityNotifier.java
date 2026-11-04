package com.ospaulos.vupth.infrastructure.mail;

import com.ospaulos.vupth.infrastructure.logging.LogMask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * FRAQUEZAS F1 (03/11): o dono e avisado por e-mail a cada troca de senha ou de e-mail, para notar a conta tomada. Best-effort: a
 * falha do e-mail e logada e nao desfaz a troca. O aviso nunca leva token, codigo nem a senha; o e-mail novo vai mascarado.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountSecurityNotifier {

    static final String FOOTER = "\n\nSe não foi você, use \"Esqueci a senha\" no app agora e fale com o suporte.\n\nEquipe VUPTH";

    private final EmailSender emailSender;

    public void passwordChanged(String toEmail) {
        send(toEmail, "VUPTH: sua senha foi trocada",
                "A senha da sua conta VUPTH acabou de ser trocada. As outras sessões foram encerradas." + FOOTER);
    }

    public void emailChanged(String oldEmail, String newEmail) {
        send(oldEmail, "VUPTH: o e-mail da sua conta foi trocado",
                "O e-mail da sua conta VUPTH foi trocado para " + LogMask.email(newEmail)
                        + ". A partir de agora o login usa o e-mail novo, e as outras sessões foram encerradas." + FOOTER);
    }

    private void send(String to, String subject, String body) {
        if (to == null || to.isBlank()) {
            return;
        }
        try {
            emailSender.send(to, subject, body);
        } catch (RuntimeException e) {
            log.warn("[AccountSecurityNotifier] aviso nao enviado para {}: {}", LogMask.email(to), e.getMessage());
        }
    }
}
