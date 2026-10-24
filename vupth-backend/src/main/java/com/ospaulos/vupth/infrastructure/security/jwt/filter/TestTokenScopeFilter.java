package com.ospaulos.vupth.infrastructure.security.jwt.filter;

import com.ospaulos.vupth.infrastructure.security.jwt.token.TestTokenPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;

/**
 * token-de-teste-sandbox: o token de teste e ADMIN, mas so abre a lista curta de {@link TestTokenPolicy#inScope}; o resto do app
 * e do painel e 403 {@code TEST_TOKEN_SCOPE}. Roda depois da autenticacao e antes da porta do painel.
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class TestTokenScopeFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean testToken = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> TestTokenPolicy.AUTHORITY.equals(a.getAuthority()));
        if (testToken && !TestTokenPolicy.inScope(request.getMethod(), request.getRequestURI())) {
            log.warn("[TestToken] fora do escopo: {} {}", request.getMethod(), request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(String.format(
                    "{\"status\":403,\"message\":\"O token de teste nao abre esta rota.\",\"code\":\"TEST_TOKEN_SCOPE\",\"timestamp\":\"%s\"}",
                    OffsetDateTime.now()));
            return;
        }
        chain.doFilter(request, response);
    }
}
