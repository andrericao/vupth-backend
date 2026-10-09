package com.ospaulos.vupth.domain.user.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Link de exclusao de conta pela web (exclusao-por-link, Andre 08/10).
 *
 * <p>Emitido por {@code POST /auth/account-deletion/request} e consumido por
 * {@code POST /auth/account-deletion/confirm}. <b>O token nunca fica em claro</b>: guardamos o SHA-256
 * dele ({@code tokenHash}), que e deterministico — e por ele que o link e achado (o bcrypt do reset nao
 * serviria: nao da para buscar por hash salgado). O token tem 256 bits aleatorios, entao o SHA-256 basta.
 * Uso unico ({@code consumed}), validade curta ({@code expiresAt}) e teto de senhas erradas
 * ({@code attempts}). Cada novo pedido apaga os anteriores do mesmo usuario.</p>
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "account_deletion_token", indexes = {
        @Index(name = "idx_account_deletion_token_user", columnList = "user_id")
})
public class AccountDeletionToken implements Serializable {

    /** Senhas erradas antes de o link morrer. */
    public static final int MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** SHA-256 (hex) do token do link. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "consumed", nullable = false)
    private boolean consumed = false;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public AccountDeletionToken(UUID userId, String tokenHash, OffsetDateTime expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    /** Ainda serve: nao usado, nao vencido e abaixo do teto de senhas erradas. */
    public boolean isUsable() {
        return !consumed && !OffsetDateTime.now().isAfter(expiresAt) && attempts < MAX_ATTEMPTS;
    }

    /** Senha errada: soma uma tentativa; na ultima o link morre. */
    public void registerWrongPassword() {
        attempts++;
        if (attempts >= MAX_ATTEMPTS) {
            consumed = true;
        }
    }
}
