package com.ospaulos.vupth.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limit das rotas de autenticacao (BAK-6 · D2): {@code POST /auth/login}, {@code /auth/register}
 * e {@code /auth/forgot-password}. Com o login e o "esqueci minha senha" dizendo quando o e-mail nao
 * tem conta, sondar e-mails em massa vira o risco — este limite e o que o contem.
 *
 * <p>Janela fixa por IP e rota, em memoria (custo zero, sem dependencia nova): ate
 * {@code vupth.auth.rate-limit.max-requests} pedidos a cada {@code window-seconds}; o excedente
 * recebe 429 {@code TOO_MANY_REQUESTS} com {@code Retry-After}. O IP vem do primeiro valor do
 * {@code X-Forwarded-For} (o Render e o Railway ficam na frente). Com duas instancias cada uma conta
 * a sua parte — o teto efetivo dobra, o que ainda barra a sondagem em massa. Desligado em dev e
 * teste ({@code enabled=false}): o Newman local e a suite fazem centenas de logins do mesmo IP.</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED = Set.of("/auth/login", "/auth/register", "/auth/forgot-password");

    private final boolean enabled;
    private final int maxRequests;
    private final long windowMillis;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public AuthRateLimitFilter(
            @Value("${vupth.auth.rate-limit.enabled:true}") boolean enabled,
            @Value("${vupth.auth.rate-limit.max-requests:10}") int maxRequests,
            @Value("${vupth.auth.rate-limit.window-seconds:60}") long windowSeconds) {
        this(enabled, maxRequests, windowSeconds, Clock.systemUTC());
    }

    AuthRateLimitFilter(boolean enabled, int maxRequests, long windowSeconds, Clock clock) {
        this.enabled = enabled;
        this.maxRequests = maxRequests;
        this.windowMillis = windowSeconds * 1000;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !"POST".equals(request.getMethod()) || !LIMITED.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = clock.millis();
        String key = clientIp(request) + " " + request.getRequestURI();
        Window window = windows.compute(key, (k, w) ->
                w == null || now - w.start >= windowMillis ? new Window(now, 1) : new Window(w.start, w.count + 1));

        if (window.count > maxRequests) {
            long retryAfter = Math.max(1, (window.start + windowMillis - now + 999) / 1000);
            log.warn("[429] RateLimit | {} {} | ip={} pedidos={} na janela", request.getMethod(),
                    request.getRequestURI(), clientIp(request), window.count);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(String.format(
                    "{\"status\":429,\"message\":\"Too many requests, try again in %d s\",\"code\":\"TOO_MANY_REQUESTS\",\"timestamp\":\"%s\"}",
                    retryAfter, OffsetDateTime.now(clock)));
            return;
        }
        if (windows.size() > 10_000) {
            windows.values().removeIf(w -> now - w.start >= windowMillis);
        }
        chain.doFilter(request, response);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private record Window(long start, int count) {}
}
