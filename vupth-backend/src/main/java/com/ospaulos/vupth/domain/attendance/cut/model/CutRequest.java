package com.ospaulos.vupth.domain.attendance.cut.model;

import com.ospaulos.vupth.domain.attendance.cut.CutRequestStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pedido de corte (BUG5 C2 — "solicitar corte"). Quando a loja esta fechada p/ novos (gate C1,
 * {@code is_open == false}), o cliente registrado nao entra sozinho na fila; em vez disso registra
 * este PEDIDO SOB APROVACAO. O profissional dono aceita (nasce um Attendance SCHEDULED na cauda) ou
 * recusa; um timer server-side auto-nega o pedido se ninguem responder ate {@code expiresAt} (5min).
 *
 * <p>Entidade PROPRIA (nao um status de Attendance) de proposito — o pedido pode ser recusado e nunca
 * virar atendimento, entao NAO pode aparecer em nenhum read-model de fila. Mesmo padrao do
 * {@code QueueSwapRequest} (#42).</p>
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "CUT_REQUEST")
public class CutRequest implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Cliente que pediu o corte (registrado — guest nao solicita). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    /** Profissional que decide (aceita/recusa). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "professional_id", nullable = false)
    private Professional professional;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "establishment_id", nullable = false)
    private Establishment establishment;

    /** Servico(s) pedidos — copiados para o Attendance no aceite. */
    @ManyToMany
    @JoinTable(
            name = "cut_request_service_item",
            joinColumns = @JoinColumn(name = "cut_request_id"),
            inverseJoinColumns = @JoinColumn(name = "service_item_id")
    )
    private List<ServiceItem> serviceItems = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CutRequestStatus status = CutRequestStatus.PENDING;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Validade do pedido: {@code createdAt + 5min}. Passou disso sem resposta -> EXPIRED (timer). */
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    /** Attendance criado no aceite (traceabilidade). Null enquanto PENDING/REFUSED/EXPIRED. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resulting_attendance_id")
    private Attendance resultingAttendance;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (status == null) {
            status = CutRequestStatus.PENDING;
        }
    }
}
