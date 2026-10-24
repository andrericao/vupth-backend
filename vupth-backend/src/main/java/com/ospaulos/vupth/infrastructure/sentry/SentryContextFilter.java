package com.ospaulos.vupth.infrastructure.sentry;

import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import io.sentry.Sentry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * sentry-contexto-e-limpeza: marca o erro com o papel de quem pediu (cliente, profissional, dono, admin), o id da loja e o
 * id do atendimento — so ids, nunca nome, e-mail ou telefone (o usuario do Sentry nao e preenchido). Roda depois da
 * autenticacao. Sem SENTRY_DSN o Sentry e no-op e isto nao custa nada.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class SentryContextFilter extends OncePerRequestFilter {

    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern ATTENDANCE = Pattern.compile("/attendances/(" + UUID + ")");
    private static final Pattern ESTABLISHMENT = Pattern.compile("/establishments/(" + UUID + ")");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String role = roleOf(SecurityContextHolder.getContext().getAuthentication());
        String attendance = firstGroup(ATTENDANCE, path);
        String establishment = firstGroup(ESTABLISHMENT, path);
        Sentry.configureScope(scope -> {
            scope.setTag("role", role);
            if (attendance != null) {
                scope.setTag("attendance_id", attendance);
            }
            if (establishment != null) {
                scope.setTag("establishment_id", establishment);
            }
        });
        chain.doFilter(request, response);
    }

    /** O papel mais alto: ADMIN, dono, profissional ou cliente; "anonymous" sem login. */
    static String roleOf(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof UserDetailsImpl)) {
            return "anonymous";
        }
        for (String role : new String[]{"ADMIN", "OWNER", "PROFESSIONAL", "EMPLOYEE", "SECRETARY", "PARTNER", "CLIENT"}) {
            for (GrantedAuthority a : auth.getAuthorities()) {
                if (a.getAuthority().equals("ROLE_" + role)) {
                    return role.toLowerCase(java.util.Locale.ROOT);
                }
            }
        }
        return "unknown";
    }

    private static String firstGroup(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
