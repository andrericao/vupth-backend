package com.ospaulos.vupth.domain.establishment.transfer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Pedido de passagem de posse da loja (desativar-conta, proposta aceita + correcao do Andre 08/10).
 *
 * <p>O dono escolhe um profissional ativo da propria loja; o pedido fica {@code PENDING} ate o aceite, a
 * recusa ou o cancelamento — <b>nao vence</b>, e o dono pode pedir a qualquer momento. So um pendente por loja.
 * Ao aceitar, so o dono da loja muda: equipe, servicos, fila e historico ficam.</p>
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "ownership_transfer", indexes = {
        @Index(name = "idx_ownership_transfer_establishment", columnList = "establishment_id"),
        @Index(name = "idx_ownership_transfer_to", columnList = "to_professional_id")
})
public class OwnershipTransfer {

    public enum Status { PENDING, ACCEPTED, REFUSED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "establishment_id", nullable = false)
    private UUID establishmentId;

    @Column(name = "from_owner_id", nullable = false)
    private UUID fromOwnerId;

    @Column(name = "to_professional_id", nullable = false)
    private UUID toProfessionalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    public OwnershipTransfer(UUID establishmentId, UUID fromOwnerId, UUID toProfessionalId) {
        this.establishmentId = establishmentId;
        this.fromOwnerId = fromOwnerId;
        this.toProfessionalId = toProfessionalId;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    /** Fecha o pedido com a resposta (ACCEPTED, REFUSED ou CANCELLED). */
    public void close(Status outcome) {
        this.status = outcome;
        this.respondedAt = OffsetDateTime.now();
    }
}
