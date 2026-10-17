package com.ospaulos.vupth.web.rest.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.api.admin.dto.AdminTotpRequest;
import com.ospaulos.vupth.domain.admin.service.AdminTotpService;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import com.ospaulos.vupth.infrastructure.security.jwt.token.JwtTokenProvider;
import jakarta.validation.Valid;
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
import java.util.List;

/**
 * O login seguro do painel (painel-de-monitoramento, fatia 3). O {@code /auth/login} segue igual; para abrir o
 * {@code /admin/**} a conta ADMIN troca o token do login por um token do painel (15 min, sem refresh) provando o
 * segundo fator: {@code GET status}, {@code POST totp/setup} + {@code totp/confirm} na primeira vez (devolve os 8
 * codigos de recuperacao), {@code POST totp/verify} nas seguintes (codigo do app ou de recuperacao). A cada token
 * entregue o admin recebe um e-mail de aviso.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/auth")
public class AdminAuthController {

    private final AdminTotpService totp;
    private final JwtTokenProvider tokens;
    private final EmailSender emailSender;

    public record Status(
            @JsonProperty("totp_enabled") boolean totpEnabled,
            @JsonProperty("verified") boolean verified) {}

    public record SetupResponse(
            @JsonProperty("secret") String secret,
            @JsonProperty("otpauth_uri") String otpauthUri) {}

    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("recovery_codes") List<String> recoveryCodes) {}

    @GetMapping("/status")
    public ResponseEntity<Status> status(@AuthenticationPrincipal UserDetailsImpl user, Authentication auth) {
        boolean verified = auth.getDetails() instanceof List<?> amr && amr.contains("totp");
        return ResponseEntity.ok(new Status(totp.isEnabled(user.getUserId()), verified));
    }

    @PostMapping("/totp/setup")
    public ResponseEntity<SetupResponse> setup(@AuthenticationPrincipal UserDetailsImpl user) {
        var setup = totp.setup(user.getUserId(), user.getUsername());
        return ResponseEntity.ok(new SetupResponse(setup.secret(), setup.otpauthUri()));
    }

    @PostMapping("/totp/confirm")
    public ResponseEntity<TokenResponse> confirm(@AuthenticationPrincipal UserDetailsImpl user,
                                                 @RequestBody @Valid AdminTotpRequest request) {
        List<String> recovery = totp.confirm(user.getUserId(), request.code());
        return ResponseEntity.ok(issue(user, recovery));
    }

    @PostMapping("/totp/verify")
    public ResponseEntity<TokenResponse> verify(@AuthenticationPrincipal UserDetailsImpl user,
                                                @RequestBody @Valid AdminTotpRequest request) {
        totp.verify(user.getUserId(), request.code());
        return ResponseEntity.ok(issue(user, null));
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
        return new TokenResponse(token, 15 * 60, recovery);
    }
}
