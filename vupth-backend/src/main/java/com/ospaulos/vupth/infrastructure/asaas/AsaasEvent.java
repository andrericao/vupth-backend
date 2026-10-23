package com.ospaulos.vupth.infrastructure.asaas;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Um aviso (webhook) do Asaas: so o necessario para o relatorio de para onde vai cada centavo, sem dado de cliente. */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "asaas_event")
public class AsaasEvent {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, length = 80)
    private String eventId;

    @Column(name = "event", nullable = false, length = 60)
    private String event;

    @Column(name = "payment_id", length = 60)
    private String paymentId;

    @Column(name = "external_reference", length = 80)
    private String externalReference;

    @Column(name = "value", precision = 12, scale = 2)
    private BigDecimal value;

    @Column(name = "net_value", precision = 12, scale = 2)
    private BigDecimal netValue;

    @Column(name = "status", length = 40)
    private String status;

    @Column(name = "billing_type", length = 30)
    private String billingType;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;
}
