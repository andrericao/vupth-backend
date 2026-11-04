package com.ospaulos.vupth.infrastructure.web;

import com.ospaulos.vupth.infrastructure.security.jwt.config.SecurityConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

/**
 * As origens de navegador autorizadas ({@code CORS_ALLOWED_ORIGINS}), num lugar so (api-no-dominio, DEC-180). O CORS, a checagem de
 * {@code Origin} das escritas por cookie (S18) e o handshake do {@code /ws} (S22) leem a mesma lista: uma origem nova entra uma vez.
 */
@Component
public class AllowedOrigins {

    private final CorsConfiguration config = new CorsConfiguration();
    private final List<String> patterns;

    public AllowedOrigins(@Value("${vupth.cors.allowed-origin-patterns:}") String raw) {
        this.patterns = SecurityConfig.parseOriginPatterns(raw);
        config.setAllowedOriginPatterns(patterns);
    }

    public List<String> patterns() {
        return patterns;
    }

    /** {@code true} se a origem casa algum padrao. Vazia ou nula: {@code false}. */
    public boolean allows(String origin) {
        return origin != null && !origin.isBlank() && config.checkOrigin(origin.trim()) != null;
    }
}
