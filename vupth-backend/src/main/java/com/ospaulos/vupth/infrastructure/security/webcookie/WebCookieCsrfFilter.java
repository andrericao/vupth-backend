package com.ospaulos.vupth.infrastructure.security.webcookie;

import com.ospaulos.vupth.infrastructure.web.AllowedOrigins;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * token-web-httponly (DEC-179): CSRF por token duplo. Vale so para o pedido que se autentica pelo cookie (sem
 * {@code Authorization}) e escreve (POST, PUT, PATCH, DELETE): o cabecalho {@value WebCookieAuth#CSRF_HEADER} tem de repetir o
 * cookie {@value WebCookieAuth#CSRF}; senao 403 {@code CSRF_INVALID}. Outro site consegue mandar o cookie, mas nao le o valor para
 * repetir. O app nativo (Bearer) e a leitura passam reto. Com a chave desligada o filtro nao faz nada.
 */
public class WebCookieCsrfFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final WebCookieAuth webCookie;
    private final AllowedOrigins origins;

    public WebCookieCsrfFilter(WebCookieAuth webCookie, AllowedOrigins origins) {
        this.webCookie = webCookie;
        this.origins = origins;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !webCookie.enabled() || SAFE.contains(request.getMethod())
                || request.getHeader("Authorization") != null
                || (WebCookieAuth.read(request, WebCookieAuth.ACCESS).isEmpty()
                    && WebCookieAuth.read(request, WebCookieAuth.REFRESH).isEmpty()
                    && WebCookieAuth.read(request, WebCookieAuth.ADMIN_REFRESH).isEmpty());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> cookie = WebCookieAuth.read(request, WebCookieAuth.CSRF);
        String header = request.getHeader(WebCookieAuth.CSRF_HEADER);
        if (cookie.isEmpty() || header == null || !MessageDigest.isEqual(
                cookie.get().getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8))) {
            deny(response, "Falta o token de CSRF.", "CSRF_INVALID");
            return;
        }
        // S18 (api-no-dominio, DEC-180): alem do token duplo, a escrita por cookie tem de vir de uma origem conhecida. Com o dominio
        // proprio, os subdominios de vupth.com sao o mesmo "site" e o SameSite=Lax nao separa um do outro; a Origin separa.
        if (!origins.allows(request.getHeader("Origin"))) {
            deny(response, "Origem nao autorizada.", "ORIGIN_INVALID");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void deny(HttpServletResponse response, String message, String code) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(String.format(
                "{\"status\":403,\"message\":\"%s\",\"code\":\"%s\",\"timestamp\":\"%s\"}", message, code, OffsetDateTime.now()));
    }
}
