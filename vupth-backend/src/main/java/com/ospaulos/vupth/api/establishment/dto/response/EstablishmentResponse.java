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

        @JsonProperty("open_status")
        String openStatus,

        @JsonProperty("status")
        String status
) {
    public static EstablishmentResponse fromEntity(Establishment establishment) {
        return new EstablishmentResponse(
                establishment.getId(),
                establishment.getServices() != null ? Set.copyOf(establishment.getServices()) : Set.of(),
                establishment.getName(),
                establishment.getServiceType(),
                establishment.getUrlImage(),
                establishment.getOwner().getId(),
                establishment.getPhone(),
                establishment.getInstagram(),
                establishment.getOpenStatus() != null ? establishment.getOpenStatus().name() : null,
                establishment.getStatus() != null ? establishment.getStatus().name() : null
        );
    }
}
