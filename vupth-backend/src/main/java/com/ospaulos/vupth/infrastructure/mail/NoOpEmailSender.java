package com.ospaulos.vupth.infrastructure.mail;

import com.ospaulos.vupth.infrastructure.logging.LogMask;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * EmailSender no-op — não chama provedor externo, apenas loga. Ativo por padrão e sempre que
 * {@code vupth.mail.provider=none} (testes/staging e produção enquanto o SMTP não estiver
 * configurado). O código de reset NUNCA vai para o log (DEC-2): quem lê o log não pode trocar a
 * senha de ninguém — só o e-mail leva o código.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vupth.mail", name = "provider", havingValue = "none", matchIfMissing = true)
public class NoOpEmailSender implements EmailSender {

    /**
     * Anuncia no boot que NENHUM e-mail sai. Sem isto, "provider=none" e indistinguivel de "SMTP
     * configurado que falhou": o endpoint responde 200 nos dois casos (anti-enumeracao) e so o log
     * do envio separa — e esse log so aparece se alguem pedir um reset. Em WARN de proposito: num
     * ambiente real isto e um defeito silencioso, nao uma informacao.
     */
    @PostConstruct
    void avisaQueNaoEnvia() {
        log.warn("[MAIL] provider=none — NENHUM e-mail sera enviado; o codigo de reset nao chega a ninguem. "
                + "Para enviar de verdade: MAIL_PROVIDER=smtp + MAIL_HOST/MAIL_USER/MAIL_PASS.");
    }

    @Override
    public void sendPasswordResetCode(String toEmail, String code) {
        log.info("[MAIL:noop] reset code pedido para {} (provider=none — nenhum e-mail enviado)", LogMask.email(toEmail));
    }

    @Override
    public void send(String toEmail, String subject, String body) {
        log.info("[MAIL:noop] '{}' para {} (provider=none — nenhum e-mail enviado)", subject, LogMask.email(toEmail));
    }
}
