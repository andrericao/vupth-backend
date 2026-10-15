package com.ospaulos.vupth.domain.support.service;

import com.ospaulos.vupth.api.support.dto.request.SupportContactRequest;
import com.ospaulos.vupth.infrastructure.mail.EmailDeliveryException;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Encaminha a mensagem do formulario do suporte ao e-mail do Andre (formulario-do-suporte, DEC-59), pelo
 * mesmo {@link EmailSender} do resto do app (Brevo na producao). O endereco vem de
 * {@code SUPPORT_INBOX_EMAIL} e nunca aparece no site. Sem endereco configurado, ou com o envio falhando,
 * o pedido falha (500) em vez de sumir calado. Depois, o painel vira a caixa de entrada.
 */
@Slf4j
@Service
public class SupportContactService {

    /** Como a categoria e o perfil aparecem no titulo do e-mail (os filtros do Andre casam com estes nomes). */
    private static final Map<String, String> CATEGORY_LABEL = Map.of(
            "DUVIDA", "Dúvida", "SUGESTAO", "Sugestão", "RECLAMACAO", "Reclamação", "PROBLEMA", "Problema",
            "PAGAMENTO", "Pagamento", "ELOGIO", "Elogio", "OUTRO", "Outro");
    private static final Map<String, String> PROFILE_LABEL = Map.of("cliente", "Cliente", "parceiro", "Parceiro");

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
        String perfil = PROFILE_LABEL.getOrDefault(request.perfil(), request.perfil());
        String categoria = CATEGORY_LABEL.getOrDefault(request.categoria(), request.categoria());
        // "[VUPTH · Cliente · Reclamação] Cobrança errada" — o titulo que os filtros do e-mail usam (15/10).
        String subject = oneLine("[VUPTH · " + perfil + " · " + categoria + "] " + request.assunto());
        String body = "Nome: " + oneLine(request.nome()) + "\n"
                + "Responder para: " + oneLine(request.email()) + "\n"
                + "Perfil: " + perfil + "\n"
                + "Categoria: " + categoria + "\n"
                + "Assunto: " + oneLine(request.assunto()) + "\n\n"
                + request.mensagem();
        emailSender.send(inbox, subject, body);
        log.info("[Suporte] mensagem encaminhada — perfil={} categoria={} assunto='{}'",
                request.perfil(), request.categoria(), oneLine(request.assunto()));
    }

    /** Assunto e cabecalhos numa linha so: quebra de linha no campo nao vira cabecalho novo no e-mail. */
    private static String oneLine(String value) {
        return value.replaceAll("[\\r\\n]+", " ").trim();
    }
}
