package com.ospaulos.vupth.domain.referral.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Um credito da bonificacao por link: a campanha, o corte, o cliente indicado, quem recebe e quanto. */
@Getter
@Entity
@NoArgsConstructor
@Table(name = "referral_credit")
public class ReferralCredit {

    @Id
    private UUID id;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "attendance_id", nullable = false)
    private UUID attendanceId;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "beneficiary_id", nullable = false)
    private UUID beneficiaryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "share", length = 12, nullable = false)
    private ReferralShare share;

    @Column(name = "amount", precision = 10, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Nulo = a receber. */
    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    public ReferralCredit(UUID campaignId, UUID attendanceId, UUID clientId, UUID beneficiaryId,
                          ReferralShare share, BigDecimal amount, OffsetDateTime createdAt) {
        this.id = UUID.randomUUID();
        this.campaignId = campaignId;
        this.attendanceId = attendanceId;
        this.clientId = clientId;
        this.beneficiaryId = beneficiaryId;
        this.share = share;
        this.amount = amount;
        this.createdAt = createdAt;
    }
}
