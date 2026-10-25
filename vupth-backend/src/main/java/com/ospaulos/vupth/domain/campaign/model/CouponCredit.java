package com.ospaulos.vupth.domain.campaign.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * O que o app deve ao profissional pelo desconto de um cupom (cupom-ativado-em-configuracoes): o cliente pagou o preco menos o
 * desconto e o profissional recebe o valor cheio — a diferenca e este credito, pago no repasse da campanha. Nulo em
 * {@code paid_at} = a receber.
 */
@Getter
@Entity
@NoArgsConstructor
@Table(name = "coupon_credit")
public class CouponCredit {

    @Id
    private UUID id;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "attendance_id", nullable = false)
    private UUID attendanceId;

    @Column(name = "beneficiary_id", nullable = false)
    private UUID beneficiaryId;

    @Column(name = "amount", precision = 10, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    public CouponCredit(UUID campaignId, UUID attendanceId, UUID beneficiaryId, BigDecimal amount, OffsetDateTime createdAt) {
        this.id = UUID.randomUUID();
        this.campaignId = campaignId;
        this.attendanceId = attendanceId;
        this.beneficiaryId = beneficiaryId;
        this.amount = amount;
        this.createdAt = createdAt;
    }
}
