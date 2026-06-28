package com.ospaulos.vupth.api.queue.swap.dto.response;

import com.ospaulos.vupth.domain.queue.swap.SwapRequestStatus;
import com.ospaulos.vupth.domain.queue.swap.model.QueueSwapRequest;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Representacao de uma proposta de troca de posicao na fila (item #42), consumida pelos dois fronts.
 */
public record SwapRequestResponse(
        UUID id,
        SwapRequestStatus status,
        UUID proposerAttendanceId,
        UUID proposerClientId,
        UUID targetAttendanceId,
        UUID targetClientId,
        UUID establishmentId,
        UUID professionalId,
        OffsetDateTime createdAt,
        OffsetDateTime respondedAt
) {
    public static SwapRequestResponse from(QueueSwapRequest s) {
        var proposer = s.getProposer();
        var target = s.getTarget();
        return new SwapRequestResponse(
                s.getId(),
                s.getStatus(),
                proposer.getId(),
                proposer.getClient() != null ? proposer.getClient().getId() : null,
                target.getId(),
                target.getClient() != null ? target.getClient().getId() : null,
                proposer.getEstablishment() != null ? proposer.getEstablishment().getId() : null,
                proposer.getProfessional() != null ? proposer.getProfessional().getId() : null,
                s.getCreatedAt(),
                s.getRespondedAt()
        );
    }
}
