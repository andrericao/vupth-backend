package com.ospaulos.vupth.domain.queue.swap.model;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.queue.swap.SwapRequestStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Proposta de troca de posicao na fila (item #42). Representa a relacao ENTRE dois atendimentos:
 * o {@code proposer} (cliente A, que inicia) e o {@code target} (cliente B, que confirma). A troca
 * dos {@code scheduledTime} so efetiva quando o alvo aceita ({@code aceiteA && aceiteB}); ate la o
 * registro fica {@code PENDING} e nada muda na fila.
 *
 * <p>Expira por evento: quando o proposer ou o target sai da fila (atendido/cancelado), as propostas
 * {@code PENDING} que os referenciam viram {@code CANCELLED} — nao ha expiracao por tempo (a regra do
 * Andre: "fica aberta ate a ultima hora; se um for atendido a pendencia deixa de existir").</p>
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "QUEUE_SWAP_REQUEST")
public class QueueSwapRequest implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Cliente A — quem propos a troca (ja consentiu por iniciar). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proposer_attendance_id", nullable = false)
    private Attendance proposer;

    /** Cliente B — quem recebe e confirma/recusa a proposta. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_attendance_id", nullable = false)
    private Attendance target;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SwapRequestStatus status = SwapRequestStatus.PENDING;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (status == null) {
            status = SwapRequestStatus.PENDING;
        }
    }
}
