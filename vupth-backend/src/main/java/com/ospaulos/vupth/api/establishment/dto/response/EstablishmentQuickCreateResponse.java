package com.ospaulos.vupth.api.establishment.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;

import java.time.LocalDateTime;
import java.util.UUID;

public record EstablishmentQuickCreateResponse(

        UUID id,
        String name,

        @JsonProperty("service_type")
        ServiceType serviceType,

        String status,

        @JsonProperty("open_status")
        String openStatus,

        @JsonProperty("owner_id")
        UUID ownerId,

        @JsonProperty("created_at")
        LocalDateTime createdAt,

        String message
) {
    public static EstablishmentQuickCreateResponse fromEntity(Establishment e) {
        return new EstablishmentQuickCreateResponse(
                e.getId(),
                e.getName(),
                e.getServiceType(),
                e.getStatus() != null ? e.getStatus().name() : EstablishmentStatus.ACTIVE.name(),
                e.getOpenStatus() != null ? e.getOpenStatus().name() : OpenStatus.CLOSED.name(),
                e.getOwner() != null ? e.getOwner().getId() : null,
                e.getCreatedAt(),
                "Establishment created! Your ID is permanent. Fill in the rest whenever you want."
        );
    }
}
