package com.ospaulos.vupth.api.establishment.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.valueObject.OperationMode;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;

import java.util.UUID;

/**
 * Um profissional vinculado a loja, TENHA OU NAO servico (Partner S8). O {@code professionals-with-queue}
 * esconde quem ainda nao cadastrou servico (#31) — esta e a visao de gestao: o dono ve o recem-vinculado
 * e o checklist de prontidao separa "1+ vinculado" de "1+ com servico".
 */
public record EstablishmentProfessionalResponse(
        UUID id,
        String name,
        String nickname,
        String specialty,
        @JsonProperty("work_status") ProfessionalStatus workStatus,
        @JsonProperty("accepting_new") Boolean acceptingNew,
        @JsonProperty("operation_mode") OperationMode operationMode,
        @JsonProperty("profile_image_url") String profileImageUrl,
        @JsonProperty("service_count") int serviceCount,
        @JsonProperty("has_service") boolean hasService
) {
    public static EstablishmentProfessionalResponse fromEntity(Professional p) {
        int services = p.getServices() == null ? 0 : p.getServices().size();
        return new EstablishmentProfessionalResponse(
                p.getId(),
                p.getBaseUser() != null ? p.getBaseUser().getFirstName() : null,
                p.getBaseUser() != null ? p.getBaseUser().getNickname() : null,
                p.getSpecialty(),
                p.getWorkStatus(),
                p.getAcceptingNew(),
                p.getOperationMode(),
                p.getBaseUser() != null ? p.getBaseUser().getProfileImageUrl() : null,
                services,
                services > 0);
    }
}
