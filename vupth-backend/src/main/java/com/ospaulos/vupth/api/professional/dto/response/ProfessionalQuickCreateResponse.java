package com.ospaulos.vupth.api.professional.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.professional.model.Professional;

import java.util.UUID;

/**
 * Retorno do cadastro simplificado de profissional.
 * The {@code id} is fixed and permanent — it never changes, even if specialty,
 * photo or display name are updated later.
 */
public record ProfessionalQuickCreateResponse(

        UUID id,
        String name,
        String specialty,
        String nickname,

        @JsonProperty("establishment_id")
        UUID establishmentId,

        @JsonProperty("establishment_name")
        String establishmentName,

        @JsonProperty("is_available")
        Boolean isAvailable,

        String message
) {
    public static ProfessionalQuickCreateResponse fromEntity(Professional p) {
        return new ProfessionalQuickCreateResponse(
                p.getId(),
                p.getBaseUser().getFirstName(),
                p.getSpecialty(),
                p.getBaseUser().getNickname(),
                p.getEstablishment() != null ? p.getEstablishment().getId() : null,
                p.getEstablishment() != null ? p.getEstablishment().getName() : null,
                p.getIsAvailable(),
                "Professional created! Your ID is permanent. Fill in the rest whenever you want."
        );
    }
}
