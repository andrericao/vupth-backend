package com.ospaulos.vupth.domain.queue.offer.model;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.queue.offer.SwapOfferDirection;
import com.ospaulos.vupth.domain.queue.offer.SwapOfferStatus;
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
 * Oferta ABERTA de troca de posicao na fila (Client C13 / Partner S11 — modelo fechado 2026-07-15).
 *
 * <p>Diferente do {@code QueueSwapRequest} (#42, DIRIGIDO a um alvo especifico), a oferta e
 * <b>1-por-1 mas ABERTA</b>: o {@code requesterClient} publica uma {@link SwapOfferDirection} e
 * qualquer cliente ELEGIVEL (por posicao/direcao na mesma fila) pode aceitar. O {@code #42} segue
 * mantido em paralelo.</p>
 *
 * <p>O solicitante pode estar <b>na fila</b> ({@code requesterAttendance} preenchido) ou <b>fora
 * dela</b> ({@code requesterAttendance == null}; posicao implicita = fim da fila). Fora da fila,
 * escolhe profissional + {@code serviceItems} para que a fila saiba recalcular os ETAs no aceite;
 * ao ser aceito nasce um Attendance (referenciado por {@code resultingAttendance}).</p>
 *
 * <p>Expira por evento: {@code EXPIRED} quando o solicitante e atendido/sai da fila; {@code ACCEPTED}
 * quando alguem aceita; {@code CANCELLED} quando o solicitante cancela. NAO expira por fechamento.</p>
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "swap_offer")
public class SwapOffer implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Cliente que publicou a oferta (sempre presente). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_client_id", nullable = false)
    private Client requesterClient;

    /** Fila-alvo: profissional (sempre presente). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "professional_id", nullable = false)
    private Professional professional;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "establishment_id", nullable = false)
    private Establishment establishment;

    /**
     * Atendimento do solicitante quando ele JA esta na fila (posicao explicita). {@code null} quando
     * o solicitante esta fora da fila (posicao implicita = fim; usa {@code serviceItems}).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_attendance_id")
    private Attendance requesterAttendance;

    /**
     * Servicos escolhidos quando o solicitante esta FORA da fila — necessarios para criar o
     * atendimento e recalcular a duracao no aceite. Vazio para oferta de quem ja esta na fila.
     */
    @ManyToMany
    @JoinTable(
            name = "swap_offer_service_item",
            joinColumns = @JoinColumn(name = "swap_offer_id"),
            inverseJoinColumns = @JoinColumn(name = "service_item_id")
    )
    private List<ServiceItem> serviceItems = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private SwapOfferDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SwapOfferStatus status = SwapOfferStatus.OPEN;

    /** Atendimento gerado quando uma oferta de FORA da fila e aceita ({@code null} nas demais). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resulting_attendance_id")
    private Attendance resultingAttendance;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    /** Solicitante na fila (posicao explicita) vs. fora da fila (posicao implicita = fim). */
    public boolean isFromQueue() {
        return requesterAttendance != null;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (status == null) {
            status = SwapOfferStatus.OPEN;
        }
    }
}
