package com.ospaulos.vupth.domain.support.service;

import com.ospaulos.vupth.api.support.dto.request.SupportContactRequest;
import com.ospaulos.vupth.infrastructure.mail.EmailDeliveryException;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Encaminha a mensagem do formulario do suporte ao e-mail do Andre (formulario-do-suporte, DEC-59), pelo
 * mesmo {@link EmailSender} do resto do app (Brevo na producao). O endereco vem de
 * {@code SUPPORT_INBOX_EMAIL} e nunca aparece no site. Sem endereco configurado, ou com o envio falhando,
 * o pedido falha (500) em vez de sumir calado. Depois, o painel vira a caixa de entrada.
 */
@Slf4j
@Service
public class SupportContactService {

    private final EmailSender emailSender;
    private final String inbox;

    public SupportContactService(EmailSender emailSender,
                                 @Value("${vupth.support.inbox-email:}") String inbox) {
        this.emailSender = emailSender;
        this.inbox = inbox == null ? "" : inbox.trim();
    }

    public void forward(SupportContactRequest request) {
        if (inbox.isEmpty()) {
            log.error("[Suporte] SUPPORT_INBOX_EMAIL vazia — mensagem de {} nao encaminhada", request.email());
            throw new EmailDeliveryException("SUPPORT_INBOX_EMAIL nao configurada", null);
        }
        String subject = oneLine("[Suporte VUPTH] " + request.perfil() + " — " + request.assunto());
        String body = "Nome: " + oneLine(request.nome()) + "\n"
                + "Responder para: " + oneLine(request.email()) + "\n"
                + "Perfil: " + request.perfil() + "\n"
                + "Assunto: " + oneLine(request.assunto()) + "\n\n"
                + request.mensagem();
        emailSender.send(inbox, subject, body);
        log.info("[Suporte] mensagem encaminhada — perfil={} assunto='{}'", request.perfil(), oneLine(request.assunto()));
    }

    /** Assunto e cabecalhos numa linha so: quebra de linha no campo nao vira cabecalho novo no e-mail. */
    private static String oneLine(String value) {
        return value.replaceAll("[\\r\\n]+", " ").trim();
    }
}
