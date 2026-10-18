package com.ospaulos.vupth.domain.payment.model;

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

/**
 * Uma linha do livro do app (relatorio-corte-e-multa-por-atendimento, DEC-93): o que um atendimento pago rendeu ao app,
 * por origem. {@code amount} e o que o cliente pagou por essa parte, {@code gatewayFee} a taxa do Asaas atribuida a ela
 * e {@code appProfit} o que sobra ao app. Nulo em {@code appProfit} = servico antigo, sem a divisao gravada.
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "app_ledger")
public class AppLedgerEntry {

    public enum Origin { SERVICE, PENALTY }

    @Id
    private UUID id;

    @Column(name = "attendance_id", nullable = false)
    private UUID attendanceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", length = 10, nullable = false)
    private Origin origin;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "gateway_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal gatewayFee;

    @Column(name = "app_profit", precision = 10, scale = 2)
    private BigDecimal appProfit;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public AppLedgerEntry(UUID attendanceId, Origin origin, BigDecimal amount, BigDecimal gatewayFee,
                          BigDecimal appProfit, OffsetDateTime createdAt) {
        this.id = UUID.randomUUID();
        this.attendanceId = attendanceId;
        this.origin = origin;
        this.amount = amount;
        this.gatewayFee = gatewayFee;
        this.appProfit = appProfit;
        this.createdAt = createdAt;
    }
}
