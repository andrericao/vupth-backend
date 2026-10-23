package com.ospaulos.vupth.infrastructure.sentry;

import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * sentry-contexto-e-limpeza: nada pessoal sai para o Sentry. Roda em todo evento antes do envio: tira o corpo, os
 * cookies e os cabecalhos de segredo do pedido, limpa a query, as mensagens e os breadcrumbs (e-mail, telefone, CPF,
 * token, chave Pix) e descarta erro de validacao 4xx (defeito do cliente, nao do servidor).
 */
@Component
public class SentryScrubber implements SentryOptions.BeforeSendCallback {

    static final String HIDDEN = "[filtrado]";

    private static final Set<String> SECRET_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie", "x-api-key", "asaas-access-token", "proxy-authorization");

    private static final Pattern SECRET_PARAM = Pattern.compile(
            "(?i)\\b(token|access_token|refresh_token|password|senha|new_password|current_password|key|chave|pix_key|email"
                    + "|e-mail|phone|telefone|code|codigo|secret|totp|cpf|cnpj)=[^&\\s]*");
    private static final Pattern JWT = Pattern.compile("eyJ[\\w-]+\\.[\\w-]+\\.[\\w-]+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer|basic)\\s+[\\w.~+/=-]+");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
    private static final Pattern CPF_CNPJ = Pattern.compile("\\b\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}\\b|\\b\\d{2}\\.?\\d{3}\\.?\\d{3}/?\\d{4}-?\\d{2}\\b");
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\w-])(?:\\+?\\d{1,3}[\\s.-]?)?(?:\\(\\d{2}\\)|\\d{2})[\\s.-]?9?\\d{4}[\\s.-]?\\d{4}(?![\\w-])");

    @Override
    public SentryEvent execute(SentryEvent event, Hint hint) {
        if (isValidationError(event.getThrowable())) {
            return null;
        }
        scrubRequest(event.getRequest());
        scrubMessage(event.getMessage());
        List<SentryException> exceptions = event.getExceptions();
        if (exceptions != null) {
            exceptions.forEach(e -> e.setValue(clean(e.getValue())));
        }
        List<Breadcrumb> crumbs = event.getBreadcrumbs();
        if (crumbs != null) {
            crumbs.forEach(SentryScrubber::scrubBreadcrumb);
        }
        return event;
    }

    static boolean isValidationError(Throwable t) {
        return t instanceof org.springframework.web.bind.MethodArgumentNotValidException
                || t instanceof ConstraintViolationException
                || t instanceof HttpMessageNotReadableException
                || t instanceof BindException;
    }

    /** Texto livre: tira o que parece segredo ou dado pessoal. */
    static String clean(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = JWT.matcher(text).replaceAll(HIDDEN);
        out = BEARER.matcher(out).replaceAll("$1 " + HIDDEN);
        out = SECRET_PARAM.matcher(out).replaceAll("$1=" + HIDDEN);
        out = EMAIL.matcher(out).replaceAll(HIDDEN);
        out = CPF_CNPJ.matcher(out).replaceAll(HIDDEN);
        return PHONE.matcher(out).replaceAll(HIDDEN);
    }

    private static void scrubRequest(Request request) {
        if (request == null) {
            return;
        }
        request.setData(null);
        request.setCookies(null);
        request.setQueryString(clean(request.getQueryString()));
        request.setUrl(clean(request.getUrl()));
        Map<String, String> headers = request.getHeaders();
        if (headers != null) {
            Map<String, String> kept = new java.util.LinkedHashMap<>();
            headers.forEach((name, value) -> {
                if (!SECRET_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                    kept.put(name, clean(value));
                }
            });
            request.setHeaders(kept);
        }
    }

    private static void scrubMessage(Message message) {
        if (message == null) {
            return;
        }
        message.setMessage(clean(message.getMessage()));
        message.setFormatted(clean(message.getFormatted()));
    }

    private static void scrubBreadcrumb(Breadcrumb crumb) {
        crumb.setMessage(clean(crumb.getMessage()));
        for (String key : new ArrayList<>(crumb.getData().keySet())) {
            Object value = crumb.getData().get(key);
            crumb.setData(key, value instanceof String s ? clean(s) : value);
        }
    }
}
