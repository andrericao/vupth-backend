package com.ospaulos.vupth.domain.client.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** O pagamento avulso das multas do cliente, por um Pix do app (pagar-multa-avulso, DEC-83). */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "penalty_payment")
public class PenaltyPayment {

    public enum Status { PENDING, PAID }

    @Id
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Ate quando o pagamento segura as multas; passado o prazo sem confirmacao, elas voltam a pendentes. */
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    /** Segura as multas: pago, ou pendente e dentro do prazo. */
    public boolean holds(OffsetDateTime now) {
        return status == Status.PAID || expiresAt.isAfter(now);
    }
}
