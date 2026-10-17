package com.ospaulos.vupth.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 * recebe 429 {@code TOO_MANY_REQUESTS} com {@code Retry-After}. O IP vem do header que o proxy da
 * plataforma escreve, {@code vupth.auth.rate-limit.ip-header} (ip-forjado-no-limite, 05/10): o primeiro
 * valor do {@code X-Forwarded-For} e o que o cliente mandar, e trocar o header a cada pedido escapava do
 * limite. No Render (Cloudflare na frente) e o {@code CF-Connecting-IP}, que o Cloudflare escreve e nao
 * deixa forjar; no Railway e o {@code X-Forwarded-For} lido pelo ULTIMO valor, o que a borda acrescenta.
 * Sem header configurado (ou ausente no pedido), conta o IP da conexao. Com duas instancias cada uma conta
 * a sua parte — o teto efetivo dobra, o que ainda barra a sondagem em massa. Desligado em dev e
 * teste ({@code enabled=false}): o Newman local e a suite fazem centenas de logins do mesmo IP.</p>
 *
 * <p>O Newman do CI (staging e prod) passa por fora do limite com o header {@code X-Vupth-CI} igual ao
 * segredo {@code vupth.auth.rate-limit.ci-secret} (env {@code RATE_LIMIT_CI_SECRET}) — decisao do
 * Andre de 05/10 (pr70-rollback-recusado): a colecao faz ~28 POSTs de auth do mesmo IP e o 429 travava
 * a homolog-last-good. Segredo vazio = ninguem passa por fora.</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    static final String CI_HEADER = "X-Vupth-CI";
    private static final Set<String> LIMITED = Set.of("/auth/login", "/auth/register", "/auth/forgot-password",
            "/auth/account-deletion/request", "/auth/account-deletion/confirm",
            // formulario-do-suporte (DEC-59): publico e manda e-mail — o mesmo limite contra spam
            "/support/contact",
            // convite-com-permissoes: publico e cria conta — o mesmo limite contra adivinhar o token
            "/admin/invitations/accept");

    /** voto-do-artigo (17/10): publico e grava no banco — o mesmo limite, com todos os artigos numa conta so. */
    private static final java.util.regex.Pattern ARTICLE_VOTE =
            java.util.regex.Pattern.compile("/support/articles/[^/]+/vote");
    private static final String ARTICLE_VOTE_ROUTE = "/support/articles/*/vote";

    private final boolean enabled;
    private final int maxRequests;
    private final long windowMillis;
    private final Clock clock;
    private final byte[] ciSecret;
    private final String ipHeader;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public AuthRateLimitFilter(
            @Value("${vupth.auth.rate-limit.enabled:true}") boolean enabled,
            @Value("${vupth.auth.rate-limit.max-requests:10}") int maxRequests,
            @Value("${vupth.auth.rate-limit.window-seconds:60}") long windowSeconds,
            @Value("${vupth.auth.rate-limit.ci-secret:}") String ciSecret,
            @Value("${vupth.auth.rate-limit.ip-header:}") String ipHeader) {
        this(enabled, maxRequests, windowSeconds, Clock.systemUTC(), ciSecret, ipHeader);
    }

    AuthRateLimitFilter(boolean enabled, int maxRequests, long windowSeconds, Clock clock) {
        this(enabled, maxRequests, windowSeconds, clock, "");
    }

    AuthRateLimitFilter(boolean enabled, int maxRequests, long windowSeconds, Clock clock, String ciSecret) {
        this(enabled, maxRequests, windowSeconds, clock, ciSecret, "");
    }

    AuthRateLimitFilter(boolean enabled, int maxRequests, long windowSeconds, Clock clock, String ciSecret,
                        String ipHeader) {
        this.ipHeader = ipHeader == null ? "" : ipHeader.trim();
        this.enabled = enabled;
        this.maxRequests = maxRequests;
        this.windowMillis = windowSeconds * 1000;
        this.clock = clock;
        this.ciSecret = ciSecret == null ? new byte[0] : ciSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !"POST".equals(request.getMethod()) || limitedRoute(request.getRequestURI()) == null
                || isCi(request);
    }

    /** A rota que conta no limite (o voto do artigo conta todos os slugs juntos), ou {@code null}. */
    private static String limitedRoute(String uri) {
        if (LIMITED.contains(uri)) {
            return uri;
        }
        return ARTICLE_VOTE.matcher(uri).matches() ? ARTICLE_VOTE_ROUTE : null;
    }

    /** O header do CI so vale com segredo configurado; a comparacao em tempo constante nao vaza o segredo. */
    private boolean isCi(HttpServletRequest request) {
        String header = request.getHeader(CI_HEADER);
        return ciSecret.length > 0 && header != null
                && MessageDigest.isEqual(ciSecret, header.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = clock.millis();
        String key = clientIp(request) + " " + limitedRoute(request.getRequestURI());
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

    /** O X-Forwarded-For vale pelo ultimo valor (o da borda); os outros headers, pelo valor inteiro. */
    private String clientIp(HttpServletRequest request) {
        return ClientAddress.ip(request, ipHeader);
    }

    private record Window(long start, int count) {}
}
