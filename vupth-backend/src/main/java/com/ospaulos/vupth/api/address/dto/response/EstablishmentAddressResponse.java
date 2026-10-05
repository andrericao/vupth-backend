package com.ospaulos.vupth.api.address.dto.response;

import com.ospaulos.vupth.domain.establishment.model.EstablishmentAddress;
import com.ospaulos.vupth.domain.address.valueObject.LocationSource;
import com.ospaulos.vupth.domain.address.valueObject.LocationStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record EstablishmentAddressResponse(

        UUID establishmentId,

        /**
         * Id do endereco — e o que o {@code PATCH /establishments/{id}/addresses/{addressId}}
         * (backlog #3) recebe. Antes dele nao havia por que expor o id; sem correcao possivel, o
         * front so lia os campos.
         */
        UUID addressId,

        @NotNull String establishmentName,

        @NotBlank(message = "Street is required")
        String street,

        @NotBlank(message = "Number is required")
        String number,

        String complement,

        @NotBlank(message = "Neighborhood is required")
        String neighborhood,

        @NotBlank(message = "City is required")
        String city,

        @NotBlank(message = "State is required")
        String state,

        @NotBlank(message = "ZIP code is required")
        @Pattern(regexp = "\\d{2}.\\d{3}-\\d{3}", message = "Invalid ZIP code format")
        String zipCode,

        Double latitude,

        Double longitude,

        /** LOCATED, PENDING ou UNRESOLVED — so LOCATED entra na busca por proximidade (Partner S25). */
        LocationStatus locationStatus,

        /** GPS (do aparelho) ou GEOCODER (achada pelo texto); nulo = sem coordenada ou desconhecida (Partner S39). */
        LocationSource locationSource

) {
        public static EstablishmentAddressResponse fromEntity(EstablishmentAddress address) {
                return new EstablishmentAddressResponse(
                        address.getEstablishment().getId(),
                        address.getAddress().getId(),
                        address.getEstablishment().getName(),
                        address.getAddress().getStreet(),
                        address.getAddress().getNumber(),
                        address.getAddress().getComplement(),
                        address.getAddress().getNeighborhood(),
                        address.getAddress().getCity(),
                        address.getAddress().getState(),
                        address.getAddress().getZipCode(),
                        address.getAddress().getLatitude(),
                        address.getAddress().getLongitude(),
                        LocationStatus.of(address.getAddress()),
                        address.getAddress().getLocationSource()
                );
        }
}

