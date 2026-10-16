package com.ospaulos.vupth.domain.referral.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/** O codigo fixo de quem indica (profissional ou dono, BAK-9): nasce no primeiro pedido e nao muda. */
@Getter
@Entity
@NoArgsConstructor
@Table(name = "referral_code")
public class ReferralCode {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "code", length = 12, nullable = false, unique = true)
    private String code;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public ReferralCode(UUID userId, String code, OffsetDateTime createdAt) {
        this.userId = userId;
        this.code = code;
        this.createdAt = createdAt;
    }
}
