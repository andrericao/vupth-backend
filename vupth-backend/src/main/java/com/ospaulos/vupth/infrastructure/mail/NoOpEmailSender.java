package com.ospaulos.vupth.infrastructure.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * EmailSender no-op — não chama provedor externo, apenas loga. Ativo por padrão e sempre que
 * {@code vupth.mail.provider=none} (testes/staging e produção enquanto o SMTP não estiver
 * configurado). O código de reset aparece no log para inspeção manual.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vupth.mail", name = "provider", havingValue = "none", matchIfMissing = true)
public class NoOpEmailSender implements EmailSender {

    @Override
    public void sendPasswordResetCode(String toEmail, String code) {
        log.info("[MAIL:noop] reset code para {} = {} (provider=none — nenhum e-mail enviado)", toEmail, code);
    }
}
