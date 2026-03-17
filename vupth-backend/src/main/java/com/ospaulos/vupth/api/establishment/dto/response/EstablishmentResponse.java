package com.ospaulos.vupth.api.establishment.dto.response;

import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import jakarta.validation.constraints.NotNull;

import java.util.Set;
import java.util.UUID;

public record EstablishmentResponse(
        @NotNull UUID id,
        @NotNull Set<ServiceType> services,
        @NotNull String name,
        String urlImage,
        @NotNull UUID ownerId
) {
    public static EstablishmentResponse fromEntity(Establishment establishment) {
        return new EstablishmentResponse(
                establishment.getId(),
                establishment.getServices(),
                establishment.getName(),
                establishment.getUrlImage(),
                establishment.getOwner().getId()
        );
    }
}
