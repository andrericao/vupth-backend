package com.ospaulos.vupth.api.affiliation.dto.response;

import com.ospaulos.vupth.domain.affiliation.model.AffiliationRequest;

import java.time.LocalDateTime;
import java.util.UUID;

public record AffiliationRequestResponse(
        UUID id,
        UUID professionalId,
        String professionalName,
        UUID establishmentId,
        String establishmentName,
        String type,
        String status,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt
) {
    public static AffiliationRequestResponse fromEntity(AffiliationRequest ar) {
        String profName = ar.getProfessional().getBaseUser() != null
                ? ar.getProfessional().getBaseUser().getFirstName()
                : ar.getProfessional().getId().toString();

        return new AffiliationRequestResponse(
                ar.getId(),
                ar.getProfessional().getId(),
                profName,
                ar.getEstablishment().getId(),
                ar.getEstablishment().getName(),
                ar.getType().name(),
                ar.getStatus().name(),
                ar.getCreatedAt(),
                ar.getResolvedAt()
        );
    }
}
