package com.ospaulos.vupth.domain.admin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** O segundo fator (TOTP) de um usuario do painel: o segredo (cifrado), se ja foi confirmado e o limite de tentativas. */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "admin_totp")
public class AdminTotp {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    /** Base32, cifrado no banco como os outros dados pessoais (S4). */
    @Convert(converter = com.ospaulos.vupth.infrastructure.crypto.EncryptedStringConverter.class)
    @Column(name = "secret", length = 400, nullable = false)
    private String secret;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private OffsetDateTime lockedUntil;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
