package com.ospaulos.vupth.infrastructure.security.webcookie;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

/**
 * token-web-httponly (DEC-179): o token vem do {@code Authorization: Bearer} (apps nativos, como sempre) e, so com a chave ligada
 * e sem esse cabecalho, do cookie HttpOnly {@value WebCookieAuth#ACCESS} da web.
 */
public class WebCookieBearerTokenResolver implements BearerTokenResolver {

    private final DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();
    private final WebCookieAuth webCookie;

    public WebCookieBearerTokenResolver(WebCookieAuth webCookie) {
        this.webCookie = webCookie;
    }

    @Override
    public String resolve(HttpServletRequest request) {
        // DEC-187: renovar e sair do painel provam a sessao pelo refresh; um token de acesso vencido junto nao pode virar 401
        String path = request.getRequestURI();
        if ("/admin/auth/refresh".equals(path) || "/admin/auth/logout".equals(path)) {
            return null;
        }
        String fromHeader = header.resolve(request);
        if (fromHeader != null || !webCookie.enabled()) {
            return fromHeader;
        }
        return WebCookieAuth.read(request, WebCookieAuth.ACCESS).orElse(null);
    }
}
