package com.ospaulos.vupth.api.establishment.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record EstablishmentGeneralInfosResponse(
        UUID id,
        String name,

        @JsonProperty("url_image")
        String urlImage,

        @JsonProperty("service_type")
        ServiceType serviceType,

        Set<ServiceType> services,

        String phone,

        String instagram,

        @JsonProperty("opening_time")
        LocalTime openingTime,

        @JsonProperty("closing_time")
        LocalTime closingTime,

        @JsonProperty("open_status")
        String openStatus,

        // "Aberto agora" efetivo. Desde 19/09 e o proprio open_status — quem abre a loja e a
        // chegada do primeiro profissional, nao o relogio (D11). Derivado, nao persistido.
        @JsonProperty("is_open")
        boolean isOpen,

        String status,

        @JsonProperty("owner_id")
        UUID ownerId,

        @JsonProperty("modified_fields")
        List<String> modifiedFields
) {
    public static EstablishmentGeneralInfosResponse fromEntity(Establishment e, List<String> modifiedFields) {
        return new EstablishmentGeneralInfosResponse(
                e.getId(),
                e.getName(),
                e.getUrlImage(),
                e.getServiceType(),
                e.getServices() != null ? Set.copyOf(e.getServices()) : Set.of(),
                e.getPhone(),
                e.getInstagram(),
                e.todayOpens(),
                e.todayCloses(),
                e.getOpenStatus() != null ? e.getOpenStatus().name() : null,
                e.isOpenNow(),
                e.getStatus() != null ? e.getStatus().name() : null,
                e.getOwner().getId(),
                modifiedFields
        );
    }
}
