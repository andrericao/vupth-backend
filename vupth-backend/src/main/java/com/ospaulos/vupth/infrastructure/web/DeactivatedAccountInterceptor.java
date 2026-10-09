package com.ospaulos.vupth.infrastructure.web;

import com.ospaulos.vupth.api.user.exception.AccountDeactivatedException;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * "So consultar" (desativar-conta, ajuste do Andre 08/10): a conta desativada entra e LE, mas nao opera.
 * Qualquer escrita (POST/PUT/PATCH/DELETE) de quem esta desativado da 403 {@code ACCOUNT_DEACTIVATED} — entrar
 * na fila, pedir corte, agendar, trocar, abrir a loja, editar o perfil. Ficam livres so as saidas: reativar,
 * apagar a conta, sair e as rotas de {@code /auth}. O app trata o 403 oferecendo "Reativar".
 *
 * <p>O usuario do principal e carregado do banco a cada request ({@code JwtAuthenticationConverter}), entao
 * o {@code deactivatedAt} aqui e o de agora.</p>
 */
@Component
public class DeactivatedAccountInterceptor implements HandlerInterceptor {

    static final Set<String> ALLOWED_WRITES = Set.of(
            "/profile/reactivate/me",
            "/profile/delete/me");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.GET.matches(request.getMethod()) || HttpMethod.OPTIONS.matches(request.getMethod())
                || HttpMethod.HEAD.matches(request.getMethod())) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserDetailsImpl principal)
                || principal.getUser() == null || !principal.getUser().isAccountDeactivated()) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/auth/") || ALLOWED_WRITES.contains(path)) {
            return true;
        }
        throw new AccountDeactivatedException();
    }
}
