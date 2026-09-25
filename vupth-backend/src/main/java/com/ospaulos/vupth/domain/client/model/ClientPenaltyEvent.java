package com.ospaulos.vupth.domain.client.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Evento de penalidade (strike) de um cliente (item #39, Fase 1 — sem pagamento).
 *
 * <p>Registrado quando o cliente cancela um atendimento SCHEDULED com o ETA da sua vez a menos de
 * 1h ({@link PenaltyType#LATE_CANCEL}). A tabela guarda o historico para auditoria/isencao; o
 * contador denormalizado {@code strike_count} no {@link Client} reflete o total para leitura rapida.
 * Fase 1 apenas registra e expoe — nenhuma cobranca ou bloqueio automatico ainda.</p>
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "client_penalty_event", indexes = {
        @Index(name = "idx_penalty_event_client", columnList = "client_id")
})
public class ClientPenaltyEvent implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private PenaltyType type;

    /** Atendimento que originou a penalidade (pode ficar null se o atendimento for removido). */
    @Column(name = "attendance_id")
    private UUID attendanceId;

    /** ETA (minutos) ate a vez do cliente no instante do evento — a janela de 1h e medida aqui. */
    @Column(name = "eta_minutes", nullable = false)
    private int etaMinutes;

    /** Valor da multa em reais (D4). Nulo nos eventos anteriores a regra e nos tipos que ainda nao cobram. */
    @Column(name = "amount", precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public ClientPenaltyEvent(Client client, PenaltyType type, UUID attendanceId, int etaMinutes) {
        this.client = client;
        this.type = type;
        this.attendanceId = attendanceId;
        this.etaMinutes = etaMinutes;
    }

    public ClientPenaltyEvent(Client client, PenaltyType type, UUID attendanceId, int etaMinutes, BigDecimal amount) {
        this(client, type, attendanceId, etaMinutes);
        this.amount = amount;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
