package com.ospaulos.vupth.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Origem so pelo Cloudflare (api-no-dominio, DEC-180 · S16). O endereco do Railway segue publico e pularia o WAF e o limite da borda;
 * com {@code vupth.edge.origin-secret} preenchido, todo pedido tem de trazer o cabecalho-segredo que so o Cloudflare escreve (Transform
 * Rule), senao 403 {@code EDGE_FORBIDDEN}. So o {@code /actuator/health} passa sem ele (o healthcheck do Railway chama direto).
 *
 * <p>Com a chave ligada, o {@code CF-Connecting-IP} passa a ser confiavel (S17): quem nao vem do Cloudflare nem chega ao filtro do
 * limite. Por isso, na producao, {@code RATE_LIMIT_IP_HEADER=CF-Connecting-IP} so junto com o segredo.
 *
 * <p>Vazio (o padrao, e o staging): o filtro nao faz nada.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class EdgeSecretFilter extends OncePerRequestFilter {

    private final byte[] secret;
    private final String header;

    public EdgeSecretFilter(@Value("${vupth.edge.origin-secret:}") String secret,
                            @Value("${vupth.edge.origin-secret-header:X-Origin-Secret}") String header) {
        this.secret = secret == null || secret.isBlank() ? null : secret.trim().getBytes(StandardCharsets.UTF_8);
        this.header = header;
        log.info("[EDGE] cabecalho-segredo do Cloudflare {}", this.secret == null ? "DESLIGADO" : "LIGADO (" + header + ")");
    }

    public boolean enabled() {
        return secret != null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (secret == null) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/actuator/health") || path.startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String sent = request.getHeader(header);
        if (sent == null || !MessageDigest.isEqual(secret, sent.trim().getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"status\":403,\"message\":\"Acesso so pelo endereco oficial.\",\"code\":\"EDGE_FORBIDDEN\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
