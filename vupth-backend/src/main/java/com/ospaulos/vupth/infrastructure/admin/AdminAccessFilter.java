package com.ospaulos.vupth.infrastructure.admin;

import com.ospaulos.vupth.domain.admin.model.AdminAudit;
import com.ospaulos.vupth.domain.admin.model.AdminPermission;
import com.ospaulos.vupth.domain.admin.repository.AdminAuditRepository;
import com.ospaulos.vupth.domain.admin.repository.AdminPermissionRepository;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
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
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A porta do painel (painel-de-monitoramento e convite-com-permissoes, fatia 1). Roda depois da autenticacao,
 * so em {@code /admin/**}: (1) confere a permissao da rota — o ADMIN sem nenhuma linha em
 * {@code admin_permission} e o dono e pode tudo; quem tem linhas so abre o que elas dizem, o resto e
 * {@code 403 ADMIN_PERMISSION_DENIED}; (2) grava em {@code admin_audit} cada pedido, inclusive os recusados.
 * Falha ao gravar a auditoria e logada e nao derruba a resposta.
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class AdminAccessFilter extends OncePerRequestFilter {

    static final int BODY_LIMIT = 2000;
    private static final Pattern SECRET = Pattern.compile(
            "(\"(?:password|new_password|current_password|token|refresh_token|code|secret|totp)\"\\s*:\\s*)\"[^\"]*\"",
            Pattern.CASE_INSENSITIVE);

    private final AdminPermissionRepository permissions;
    private final AdminAuditRepository audits;

    public AdminAccessFilter(AdminPermissionRepository permissions, AdminAuditRepository audits) {
        this.permissions = permissions;
        this.audits = audits;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals("/admin") || path.startsWith("/admin/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID adminId = currentUserId();
        if (adminId == null) {
            chain.doFilter(request, response); // a seguranca ja barrou quem nao e ADMIN; nao ha quem auditar
            return;
        }
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request, BODY_LIMIT);
        AdminPermission required = AdminPermission.forPath(request.getRequestURI());
        Set<AdminPermission> granted = permissions.permissionsOf(adminId);
        boolean owner = granted.isEmpty();
        if (!owner && (required == null || !granted.contains(required))) {
            deny(response, required);
            audit(adminId, wrapped, response.getStatus());
            return;
        }
        try {
            chain.doFilter(wrapped, response);
        } finally {
            audit(adminId, wrapped, response.getStatus());
        }
    }

    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl user) {
            return user.getUserId();
        }
        return null;
    }

    private static void deny(HttpServletResponse response, AdminPermission required) throws IOException {
        String message = new com.ospaulos.vupth.domain.admin.exception.AdminPermissionDeniedException(required).getMessage();
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(String.format(
                "{\"status\":403,\"message\":\"%s\",\"code\":\"ADMIN_PERMISSION_DENIED\",\"timestamp\":\"%s\"}",
                message, OffsetDateTime.now()));
    }

    private void audit(UUID adminId, ContentCachingRequestWrapper request, int status) {
        try {
            AdminAudit row = new AdminAudit();
            row.setId(UUID.randomUUID());
            row.setAdminId(adminId);
            row.setMethod(request.getMethod());
            row.setPath(cut(request.getRequestURI(), 300));
            row.setQuery(cut(request.getQueryString(), 500));
            row.setBody(body(request));
            row.setStatus(status);
            row.setCreatedAt(OffsetDateTime.now());
            audits.save(row);
        } catch (RuntimeException e) {
            log.error("[AdminAudit] falha ao gravar {} {}: {}", request.getMethod(), request.getRequestURI(),
                    e.getMessage());
        }
    }

    /** O corpo das escritas, sem segredos; leitura nao tem corpo para guardar. */
    static String body(ContentCachingRequestWrapper request) {
        byte[] content = request.getContentAsByteArray();
        if (content.length == 0) {
            return null;
        }
        return cut(redact(new String(content, StandardCharsets.UTF_8)), BODY_LIMIT);
    }

    static String redact(String json) {
        return SECRET.matcher(json).replaceAll("$1\"***\"");
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
