package com.ospaulos.vupth.api.establishment.dto.request;

import com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import jakarta.validation.constraints.NotNull;

import java.util.Set;
import java.util.UUID;

public record EstablishmentRequest(
        @NotNull UUID ownerID,
        @NotNull Set<ServiceType> services,
        @NotNull String name,
        @NotNull String urlImage,
        @NotNull EstablishmentStatus status,
        @NotNull OpenStatus openStatus) {
}
