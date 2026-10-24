package com.ospaulos.vupth.domain.debt.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * O que o profissional deve ao app por um corte pago por fora (divida-por-fora-e-abatimento, DEC-109/115): o preco menos o que ele
 * recebe, inteiro. {@code simulated} = nasceu antes de o Painel ligar a divida real: so teste, ninguem deve.
 */
@Getter
@Entity
@NoArgsConstructor
@Table(name = "professional_debt")
public class ProfessionalDebt {

    @Id
    private UUID id;

    @Column(name = "professional_id", nullable = false)
    private UUID professionalId;

    @Column(name = "attendance_id", nullable = false)
    private UUID attendanceId;

    @Column(name = "amount", precision = 10, scale = 2, nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", length = 20, nullable = false)
    private DebtOrigin origin;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private DebtStatus status = DebtStatus.OPEN;

    @Column(name = "simulated", nullable = false)
    private boolean simulated;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Setter
    @Column(name = "settled_at")
    private OffsetDateTime settledAt;

    public ProfessionalDebt(UUID professionalId, UUID attendanceId, BigDecimal amount, DebtOrigin origin,
                            boolean simulated, OffsetDateTime createdAt) {
        this.id = UUID.randomUUID();
        this.professionalId = professionalId;
        this.attendanceId = attendanceId;
        this.amount = amount;
        this.origin = origin;
        this.simulated = simulated;
        this.createdAt = createdAt;
    }
}
