package com.ospaulos.vupth.api.establishment.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import jakarta.validation.constraints.NotNull;

import java.util.Set;
import java.util.UUID;

public record EstablishmentResponse(
        @NotNull UUID id,
        @NotNull Set<ServiceType> services,
        @NotNull String name,
        ServiceType serviceType,
        String urlImage,
        @NotNull UUID ownerId,

        String phone,
        String instagram,

        // Menor tempo de espera (min) entre os profissionais da loja, em minutos.
        // null quando a loja nao tem profissional com servico (sem disponibilidade).
        // Preenchido apenas na busca/listagem publica (GET /establishment).
        @JsonProperty("estimated_wait_minutes")
        Integer estimatedWaitMinutes,

        @JsonProperty("open_status")
        String openStatus,

        @JsonProperty("status")
        String status
) {
    public static EstablishmentResponse fromEntity(Establishment establishment) {
        return fromEntity(establishment, null);
    }

    public static EstablishmentResponse fromEntity(Establishment establishment, Integer estimatedWaitMinutes) {
        return new EstablishmentResponse(
                establishment.getId(),
                establishment.getServices() != null ? Set.copyOf(establishment.getServices()) : Set.of(),
                establishment.getName(),
                establishment.getServiceType(),
                establishment.getUrlImage(),
                establishment.getOwner().getId(),
                establishment.getPhone(),
                establishment.getInstagram(),
                estimatedWaitMinutes,
                establishment.getOpenStatus() != null ? establishment.getOpenStatus().name() : null,
                establishment.getStatus() != null ? establishment.getStatus().name() : null
        );
    }
}
