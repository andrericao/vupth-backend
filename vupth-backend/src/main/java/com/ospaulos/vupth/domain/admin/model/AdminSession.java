package com.ospaulos.vupth.domain.admin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A sessao de quem entrou no painel (DEC-187): dura enquanto for usada, ate o teto. Guarda so o hash do refresh. */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "admin_session")
public class AdminSession {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", length = 64, nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "previous_hash", length = 64)
    private String previousHash;

    @Column(name = "rotated_at")
    private OffsetDateTime rotatedAt;

    /** A duracao escolhida quando a sessao abriu; mudar a escolha vale para a proxima entrada. */
    @Column(name = "idle_minutes", nullable = false)
    private int idleMinutes;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "last_used_at", nullable = false)
    private OffsetDateTime lastUsedAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;
}
