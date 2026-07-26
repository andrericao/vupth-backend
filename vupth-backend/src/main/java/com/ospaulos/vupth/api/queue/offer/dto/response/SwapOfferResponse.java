package com.ospaulos.vupth.api.queue.offer.dto.response;

import com.ospaulos.vupth.domain.queue.offer.SwapOfferDirection;
import com.ospaulos.vupth.domain.queue.offer.SwapOfferStatus;
import com.ospaulos.vupth.domain.queue.offer.model.SwapOffer;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Representacao de uma oferta aberta de troca de posicao (Client C13), consumida pelo front-client.
 */
public record SwapOfferResponse(
        UUID id,
        SwapOfferStatus status,
        SwapOfferDirection direction,
        UUID requesterClientId,
        UUID requesterAttendanceId,
        UUID professionalId,
        UUID establishmentId,
        List<UUID> serviceItemIds,
        UUID resultingAttendanceId,
        OffsetDateTime createdAt,
        OffsetDateTime respondedAt
) {
    public static SwapOfferResponse from(SwapOffer o) {
        return new SwapOfferResponse(
                o.getId(),
                o.getStatus(),
                o.getDirection(),
                o.getRequesterClient() != null ? o.getRequesterClient().getId() : null,
                o.getRequesterAttendance() != null ? o.getRequesterAttendance().getId() : null,
                o.getProfessional() != null ? o.getProfessional().getId() : null,
                o.getEstablishment() != null ? o.getEstablishment().getId() : null,
                o.getServiceItems() == null ? List.of()
                        : o.getServiceItems().stream().map(ServiceItem::getId).toList(),
                o.getResultingAttendance() != null ? o.getResultingAttendance().getId() : null,
                o.getCreatedAt(),
                o.getRespondedAt()
        );
    }
}
