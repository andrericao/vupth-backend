package com.ospaulos.vupth.web.rest.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.api.admin.dto.AdminTotpRequest;
import com.ospaulos.vupth.domain.admin.model.AdminPermission;
import com.ospaulos.vupth.domain.admin.repository.AdminPermissionRepository;
import com.ospaulos.vupth.domain.admin.service.AdminSessionService;
import com.ospaulos.vupth.domain.admin.service.AdminTotpService;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import com.ospaulos.vupth.infrastructure.security.jwt.token.JwtTokenProvider;
import com.ospaulos.vupth.infrastructure.security.webcookie.WebCookieAuth;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * O login seguro do painel (painel-de-monitoramento, fatia 3). O {@code /auth/login} segue igual; para abrir o
 * {@code /admin/**} a conta ADMIN troca o token do login por um token do painel (15 min) provando o
 * segundo fator: {@code GET status}, {@code POST totp/setup} + {@code totp/confirm} na primeira vez (devolve os 8
 * codigos de recuperacao), {@code POST totp/verify} nas seguintes (codigo do app ou de recuperacao). A cada token
 * entregue pelo segundo fator o admin recebe um e-mail de aviso.
 *
 * <p>sessao-configuravel-do-painel (DEC-187): quem entra tambem recebe um refresh, e {@code POST refresh} troca o token sem senha
 * nem codigo enquanto a sessao viver. A duracao fica em {@link AdminSessionSettingsController}.</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/auth")
public class AdminAuthController {

    private static final long TOKEN_SECONDS = 15 * 60;

    private final AdminTotpService totp;
    private final AdminSessionService sessions;
    private final JwtTokenProvider tokens;
    private final EmailSender emailSender;
    private final AdminPermissionRepository permissionRepository;
    private final WebCookieAuth webCookieAuth;

    public record Status(
            @JsonProperty("totp_enabled") boolean totpEnabled,
            @JsonProperty("verified") boolean verified,
            @JsonProperty("owner") boolean owner,
            @JsonProperty("permissions") List<AdminPermission> permissions) {}

    public record SetupResponse(
            @JsonProperty("secret") String secret,
            @JsonProperty("otpauth_uri") String otpauthUri) {}

    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("recovery_codes") List<String> recoveryCodes,
            @JsonProperty("refresh_token") @JsonInclude(JsonInclude.Include.NON_NULL) String refreshToken,
            @JsonProperty("session_expires_in") @JsonInclude(JsonInclude.Include.NON_NULL) Long sessionExpiresIn) {}

    public record RefreshRequest(@JsonProperty("refresh_token") @Size(max = 100) String refreshToken) {}

    @GetMapping("/status")
    public ResponseEntity<Status> status(@AuthenticationPrincipal UserDetailsImpl user, Authentication auth) {
        boolean verified = auth.getDetails() instanceof List<?> amr && amr.contains("totp");
        // O dono (sem nenhuma linha de permissao) pode tudo: o painel recebe a lista cheia para esconder so o que falta
        Set<AdminPermission> granted = permissionRepository.permissionsOf(user.getUserId());
        boolean owner = granted.isEmpty();
        List<AdminPermission> permissions = (owner ? EnumSet.allOf(AdminPermission.class) : granted).stream()
                .sorted().toList();
        return ResponseEntity.ok(new Status(totp.isEnabled(user.getUserId()), verified, owner, permissions));
    }

    @PostMapping("/totp/setup")
    public ResponseEntity<SetupResponse> setup(@AuthenticationPrincipal UserDetailsImpl user) {
        var setup = totp.setup(user.getUserId(), user.getUsername());
        return ResponseEntity.ok(new SetupResponse(setup.secret(), setup.otpauthUri()));
    }

    @PostMapping("/totp/confirm")
    public ResponseEntity<TokenResponse> confirm(@AuthenticationPrincipal UserDetailsImpl user,
                                                 @RequestBody @Valid AdminTotpRequest request,
                                                 HttpServletRequest http,
                                                 HttpServletResponse httpResponse) {
        List<String> recovery = totp.confirm(user.getUserId(), request.code());
        return ResponseEntity.ok(toWeb(issue(user, recovery), http, httpResponse));
    }

    @PostMapping("/totp/verify")
    public ResponseEntity<TokenResponse> verify(@AuthenticationPrincipal UserDetailsImpl user,
                                                @RequestBody @Valid AdminTotpRequest request,
                                                HttpServletRequest http,
                                                HttpServletResponse httpResponse) {
        totp.verify(user.getUserId(), request.code());
        return ResponseEntity.ok(toWeb(issue(user, null), http, httpResponse));
    }

    /** Publico: quem prova a sessao e o refresh (no corpo, ou no cookie no modo cookie). Falhou: 401 e o painel pede o codigo. */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@RequestBody(required = false) @Valid RefreshRequest request,
                                                 HttpServletRequest http,
                                                 HttpServletResponse httpResponse) {
        var renewed = sessions.refresh(presented(request, http));
        return ResponseEntity.ok(toWeb(new TokenResponse(renewed.accessToken(), TOKEN_SECONDS, null,
                renewed.refreshToken(), renewed.expiresIn()), http, httpResponse));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) @Valid RefreshRequest request,
                                       HttpServletRequest http,
                                       HttpServletResponse httpResponse) {
        sessions.close(presented(request, http));
        if (webCookieAuth.wants(http)) {
            webCookieAuth.clearAdmin(httpResponse);
        }
        return ResponseEntity.noContent().build();
    }

    private String presented(RefreshRequest request, HttpServletRequest http) {
        if (request != null && request.refreshToken() != null) {
            return request.refreshToken();
        }
        return webCookieAuth.enabled() ? WebCookieAuth.read(http, WebCookieAuth.ADMIN_REFRESH).orElse(null) : null;
    }

    /**
     * token-web-httponly (DEC-179, pedido do Painel): no modo cookie o token do painel vai so no cookie HttpOnly de acesso e sai do
     * corpo, e o refresh vai no cookie dele (os codigos de recuperacao continuam no corpo, mostrados uma vez). Chave desligada
     * ou sem pedir: igual a antes.
     */
    private TokenResponse toWeb(TokenResponse issued, HttpServletRequest http,
                                HttpServletResponse httpResponse) {
        if (!webCookieAuth.wants(http)) {
            return issued;
        }
        webCookieAuth.writeAccess(httpResponse, issued.accessToken(), issued.expiresIn());
        if (issued.refreshToken() != null) {
            webCookieAuth.writeAdminRefresh(http, httpResponse, issued.refreshToken(), issued.sessionExpiresIn());
        }
        return new TokenResponse(null, issued.expiresIn(), issued.recoveryCodes(), null, issued.sessionExpiresIn());
    }

    private TokenResponse issue(UserDetailsImpl user, List<String> recovery) {
        String token = tokens.createAdminToken(user);
        try {
            emailSender.send(user.getUsername(), "Novo acesso ao painel VUPTH",
                    "Houve um acesso ao painel VUPTH com a sua conta em " + OffsetDateTime.now()
                            + ". Se nao foi voce, avise o administrador e troque a senha.");
        } catch (RuntimeException e) {
            log.error("[AdminAuth] falha ao avisar o acesso por e-mail user={}: {}", user.getUserId(), e.getMessage());
        }
        var session = sessions.open(user.getUserId());
        return new TokenResponse(token, TOKEN_SECONDS, recovery, session.refreshToken(), session.expiresIn());
    }
}
