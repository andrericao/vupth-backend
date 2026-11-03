package com.ospaulos.vupth.domain.feedback.service;

import com.ospaulos.vupth.api.feedback.dto.FeedbackRequest;
import com.ospaulos.vupth.infrastructure.logging.LogMask;
import com.ospaulos.vupth.infrastructure.mail.EmailDeliveryException;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Feedback do app por e-mail (feedback-por-email, DEC-167). O destino vem de {@code FEEDBACK_EMAIL} e nunca aparece
 * em resposta nem no docs. O e-mail do usuario vai no corpo ("Responder para"); nunca o token. Sem destino
 * configurado, ou com o envio falhando, o pedido falha (500) em vez de sumir calado.
 */
@Slf4j
@Service
public class FeedbackService {

    private final EmailSender emailSender;
    private final FeedbackQuota quota;
    private final String inbox;

    public FeedbackService(EmailSender emailSender, FeedbackQuota quota,
                           @Value("${vupth.feedback.inbox-email:}") String inbox) {
        this.emailSender = emailSender;
        this.quota = quota;
        this.inbox = inbox == null ? "" : inbox.trim();
    }

    public void send(UUID userId, String userEmail, FeedbackRequest request) {
        quota.take(userId);
        if (inbox.isEmpty()) {
            log.error("[Feedback] FEEDBACK_EMAIL vazia — feedback de {} nao encaminhado", LogMask.email(userEmail));
            throw new EmailDeliveryException("FEEDBACK_EMAIL nao configurada", null);
        }
        String app = "PARTNER".equals(request.app()) ? "Partner" : "Client";
        String version = request.appVersion() == null || request.appVersion().isBlank() ? "?" : oneLine(request.appVersion());
        // "[VUPTH · Client · 1.4.2] feedback" — o titulo que o filtro do e-mail usa
        String subject = "[VUPTH · " + app + " · " + version + "] feedback";
        String body = "Responder para: " + oneLine(userEmail) + "\n"
                + "Usuario: " + userId + "\n"
                + "App: " + app + " " + version + "\n"
                + "Aparelho: " + (request.device() == null ? "?" : oneLine(request.device())) + "\n"
                + "Categoria: " + (request.category() == null ? "-" : request.category()) + "\n\n"
                + request.text();
        emailSender.send(inbox, subject, body);
        log.info("[Feedback] encaminhado — app={} categoria={}", request.app(), request.category());
    }

    private static String oneLine(String value) {
        return value.replaceAll("[\\r\\n]+", " ").trim();
    }
}
