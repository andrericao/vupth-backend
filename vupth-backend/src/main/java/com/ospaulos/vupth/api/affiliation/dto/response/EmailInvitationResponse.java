package com.ospaulos.vupth.api.affiliation.dto.response;

import com.ospaulos.vupth.domain.affiliation.model.EmailInvitation;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Convite por e-mail que espera a conta (convite-sem-conta). */
public record EmailInvitationResponse(
        UUID id,
        UUID establishmentId,
        String email,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime convertedAt,
        UUID affiliationId
) {
    public static EmailInvitationResponse fromEntity(EmailInvitation i) {
        return new EmailInvitationResponse(
                i.getId(),
                i.getEstablishment().getId(),
                i.getEmail(),
                i.getStatus().name(),
                i.getCreatedAt(),
                i.getConvertedAt(),
                i.getAffiliationRequest() != null ? i.getAffiliationRequest().getId() : null);
    }
}
