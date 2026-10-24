package com.ospaulos.vupth.infrastructure.ops;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * admin-alertas-push-e-5xx: conta no {@link OpsCounter} toda resposta 5xx (tambem a excecao que escapa de tudo). So o total por
 * hora: sem rota, sem usuario, sem corpo.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class Http5xxCounterFilter extends OncePerRequestFilter {

    private final OpsCounter counter;

    public Http5xxCounterFilter(OpsCounter counter) {
        this.counter = counter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            counter.increment(OpsCounter.HTTP_5XX);
            throw e;
        }
        if (response.getStatus() >= 500) {
            counter.increment(OpsCounter.HTTP_5XX);
        }
    }
}
