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

/** Um codigo de recuperacao do painel: so o hash fica no banco, e cada um vale uma vez. */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "admin_recovery_code")
public class AdminRecoveryCode {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "code_hash", length = 64, nullable = false)
    private String codeHash;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;
}
