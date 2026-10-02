package com.ospaulos.vupth.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Mede cada request de ponta a ponta (autenticacao inclusa) e loga em WARN o que passar de
 * {@code vupth.http.slow-ms}. Responde "qual chamada demorou?" pelo log do Render, sem expor
 * /actuator/metrics (fechado desde 26/08). Os probes do actuator ficam de fora.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SlowRequestLogFilter extends OncePerRequestFilter {

    private final long slowMs;

    public SlowRequestLogFilter(@Value("${vupth.http.slow-ms:800}") long slowMs) {
        this.slowMs = slowMs;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms >= slowMs) {
                log.warn("[SLOW] {} {} -> {} em {} ms", request.getMethod(), request.getRequestURI(),
                        response.getStatus(), ms);
            } else if (log.isDebugEnabled()) {
                log.debug("[HTTP] {} {} -> {} em {} ms", request.getMethod(), request.getRequestURI(),
                        response.getStatus(), ms);
            }
        }
    }
}
