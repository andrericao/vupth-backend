package com.ospaulos.vupth.domain.referral.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * pagamento-ao-profissional (Andre, 06/11): uma linha do historico do que o app pagou. Imutavel, aqui e no banco (gatilho): o estorno
 * e outra linha ({@code REVERSAL}, com o mesmo valor positivo e {@code reversalOf}). {@code paidAt} e o mesmo gravado nos premios
 * pagos: e por ele, com a campanha e a pessoa, que o estorno acha o que devolver ao "a pagar".
 */
@Getter
@Entity
@Immutable
@NoArgsConstructor
@Table(name = "professional_payment")
public class ProfessionalPayment {

    public enum Type { PAYMENT, REVERSAL }

    @Id
    private UUID id;

    @Column(name = "professional_id", nullable = false)
    private UUID professionalId;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 10, nullable = false)
    private Type type;

    @Column(name = "amount", precision = 10, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "paid_at", nullable = false)
    private OffsetDateTime paidAt;

    @Column(name = "note", length = 200)
    private String note;

    @Column(name = "reversal_of")
    private UUID reversalOf;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    private ProfessionalPayment(UUID professionalId, UUID campaignId, Type type, BigDecimal amount, OffsetDateTime paidAt,
                                String note, UUID reversalOf, UUID createdBy, OffsetDateTime createdAt) {
        this.id = UUID.randomUUID();
        this.professionalId = professionalId;
        this.campaignId = campaignId;
        this.type = type;
        this.amount = amount;
        this.paidAt = paidAt;
        this.note = note;
        this.reversalOf = reversalOf;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public static ProfessionalPayment payment(UUID professionalId, UUID campaignId, BigDecimal amount, OffsetDateTime paidAt,
                                              String note, UUID createdBy) {
        return new ProfessionalPayment(professionalId, campaignId, Type.PAYMENT, amount, paidAt, note, null, createdBy, paidAt);
    }

    public ProfessionalPayment reversal(String reason, UUID createdBy, OffsetDateTime now) {
        return new ProfessionalPayment(professionalId, campaignId, Type.REVERSAL, amount, now, reason, id, createdBy, now);
    }
}
