package com.ospaulos.vupth.api.professional.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.professional.model.Professional;

import java.util.UUID;

/**
 * Item da busca de profissionais (GET /professional?name=&specialty=).
 * Usado pelo owner para localizar profissionais e enviar convite de afiliacao.
 */
public record ProfessionalSearchResponse(
        UUID id,
        String name,
        String specialty,
        String nickname,

        @JsonProperty("is_available")
        Boolean isAvailable,

        // S22: foto de perfil (URL publica no R2); null sem foto.
        @JsonProperty("profile_image_url")
        String profileImageUrl
) {
    public static ProfessionalSearchResponse fromEntity(Professional p) {
        return new ProfessionalSearchResponse(
                p.getId(),
                p.getBaseUser() != null ? p.getBaseUser().getFirstName() : null,
                p.getSpecialty(),
                p.getBaseUser() != null ? p.getBaseUser().getNickname() : null,
                p.getIsAvailable(),
                p.getBaseUser() != null ? p.getBaseUser().getProfileImageUrl() : null
        );
    }
}
