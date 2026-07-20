package com.ospaulos.vupth.domain.user.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Código de reset de senha ("esqueci minha senha", backlog #1).
 *
 * <p>Emitido por {@code POST /auth/forgot-password} e consumido por {@code POST /auth/reset-password}.
 * <b>Nunca guardamos o código em claro</b> — só o {@code codeHash} (bcrypt), conferido com
 * {@code PasswordEncoder.matches}. O par (usuário + código) é de <b>uso único</b> ({@code consumed}),
 * tem <b>validade curta</b> ({@code expiresAt}) e limita {@code attempts} para frear brute-force dos
 * 6 dígitos. Cada novo pedido invalida os anteriores do mesmo usuário.</p>
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "password_reset_code", indexes = {
        @Index(name = "idx_password_reset_code_user", columnList = "user_id")
})
public class PasswordResetCode implements Serializable {

    /** Teto de tentativas erradas antes de o código ser invalidado (anti-brute-force). */
    public static final int MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Dono do código (id do {@code base_user}). */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Hash bcrypt do código de 6 dígitos (nunca o código em claro). */
    @Column(name = "code_hash", nullable = false, length = 72)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    /** True quando já foi usado (ou invalidado por excesso de tentativas). Uso único. */
    @Column(name = "consumed", nullable = false)
    private boolean consumed = false;

    /** Tentativas erradas acumuladas; ao passar de {@link #MAX_ATTEMPTS} o código morre. */
    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public PasswordResetCode(UUID userId, String codeHash, OffsetDateTime expiresAt) {
        this.userId = userId;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    /** Expirado se passou de {@code expiresAt}. */
    public boolean isExpired() {
        return OffsetDateTime.now().isAfter(expiresAt);
    }

    /** Ainda utilizável: não consumido, não expirado e abaixo do teto de tentativas. */
    public boolean isUsable() {
        return !consumed && !isExpired() && attempts < MAX_ATTEMPTS;
    }
}
