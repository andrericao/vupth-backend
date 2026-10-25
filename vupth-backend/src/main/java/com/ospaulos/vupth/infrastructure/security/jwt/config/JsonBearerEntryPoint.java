package com.ospaulos.vupth.infrastructure.security.jwt.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.OffsetDateTime;

/**
 * O 401 que diz o motivo (newman-asaas-ajustes): o status e o {@code WWW-Authenticate: Bearer} seguem como eram, e o corpo traz
 * um codigo — {@code TOKEN_MISSING}, {@code TOKEN_EXPIRED}, {@code TEST_TOKEN_REFUSED} ou {@code TOKEN_INVALID} — sem a mensagem
 * interna nem qualquer dado do token ou da conta.
 */
@Component
public class JsonBearerEntryPoint implements AuthenticationEntryPoint {

    private final BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        bearer.commence(request, response, exception); // 401 e o cabecalho WWW-Authenticate, como antes
        String code = codeOf(request, exception);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(String.format("{\"status\":401,\"message\":\"%s\",\"code\":\"%s\",\"timestamp\":\"%s\"}",
                messageOf(code), code, OffsetDateTime.now()));
    }

    static String codeOf(HttpServletRequest request, AuthenticationException exception) {
        if (request.getHeader("Authorization") == null) {
            return "TOKEN_MISSING";
        }
        for (Throwable t = exception; t != null; t = t.getCause()) {
            String message = t.getMessage() == null ? "" : t.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (t instanceof DisabledException && message.contains("test token")) {
                return "TEST_TOKEN_REFUSED";
            }
            if (message.contains("expired")) {
                return "TOKEN_EXPIRED";
            }
        }
        return "TOKEN_INVALID";
    }

    private static String messageOf(String code) {
        return switch (code) {
            case "TOKEN_MISSING" -> "Falta o token de acesso.";
            case "TOKEN_EXPIRED" -> "O token de acesso venceu: entre de novo.";
            case "TEST_TOKEN_REFUSED" -> "O token de teste esta desligado, fora do sandbox ou foi revogado.";
            default -> "O token de acesso nao vale.";
        };
    }
}
