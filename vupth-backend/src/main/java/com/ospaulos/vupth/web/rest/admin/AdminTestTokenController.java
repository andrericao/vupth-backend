package com.ospaulos.vupth.web.rest.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import com.ospaulos.vupth.infrastructure.security.jwt.token.JwtTokenProvider;
import com.ospaulos.vupth.infrastructure.security.jwt.token.TestTokenException;
import com.ospaulos.vupth.infrastructure.security.jwt.token.TestTokenPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * token-de-teste-sandbox: o ADMIN ja autenticado (o login do Authorize, com o segundo fator) emite o token de 7 dias que o Andre
 * entrega ao Newman. So com {@code TEST_TOKEN_ENABLED} ligada e o Asaas em sandbox; o token nao e guardado em lugar nenhum e um
 * token de teste nao emite outro (a lista de escopo nao inclui esta rota). Revogacao: um ({@code jti}) ou todos.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/test-tokens")
public class AdminTestTokenController {

    private final JwtTokenProvider tokens;
    private final TestTokenPolicy policy;

    public record Issued(@JsonProperty("token") String token, @JsonProperty("expires_at") Instant expiresAt,
                         @JsonProperty("jti") String jti, @JsonProperty("valid_days") long validDays) {}

    @PostMapping
    public ResponseEntity<Issued> issue(@AuthenticationPrincipal UserDetailsImpl admin) {
        if (!policy.usable()) {
            throw new TestTokenException(HttpStatus.FORBIDDEN, "TEST_TOKEN_DISABLED",
                    "O token de teste esta desligado ou o Asaas nao esta em sandbox.");
        }
        UUID jti = UUID.randomUUID();
        Instant issuedAt = Instant.now();
        String token = tokens.createTestToken(admin, jti);
        return ResponseEntity.status(HttpStatus.CREATED).body(new Issued(token, issuedAt.plus(TestTokenPolicy.TTL),
                jti.toString(), TestTokenPolicy.TTL.toDays()));
    }

    @DeleteMapping("/{jti}")
    public ResponseEntity<Void> revoke(@PathVariable String jti, @AuthenticationPrincipal UserDetailsImpl admin) {
        policy.revoke(jti, admin.getUserId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> revokeAll(@AuthenticationPrincipal UserDetailsImpl admin) {
        policy.revokeAll(admin.getUserId());
        return ResponseEntity.noContent().build();
    }
}
