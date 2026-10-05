package com.ospaulos.vupth.api.address.dto.response;

import com.ospaulos.vupth.domain.address.model.Address;
import com.ospaulos.vupth.domain.address.valueObject.LocationSource;
import com.ospaulos.vupth.domain.address.valueObject.LocationStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record AddressResponse(

        /**
         * Id do endereco — o que o {@code PATCH /establishments/{id}/addresses/{addressId}} recebe.
         * Sem ele no read-back, o Partner so corrigia pelo POST, que cria outro endereco (S38).
         */
        UUID addressId,

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

        String reference,

        Double latitude,

        Double longitude,

        /** LOCATED, PENDING ou UNRESOLVED — so LOCATED entra na busca por proximidade (Partner S25). */
        LocationStatus locationStatus,

        /** GPS (do aparelho) ou GEOCODER (achada pelo texto); nulo = sem coordenada ou desconhecida (Partner S39). */
        LocationSource locationSource
) {
    /**
     * Constroi o endereco embutido a partir do value object {@link Address}.
     * Retorna {@code null} quando a loja nao tem endereco (Partner BUG4: front le {@code data.address}).
     */
    public static AddressResponse fromEntity(Address address) {
        if (address == null) {
            return null;
        }
        return new AddressResponse(
                address.getId(),
                address.getStreet(),
                address.getNumber(),
                address.getComplement(),
                address.getNeighborhood(),
                address.getCity(),
                address.getState(),
                address.getZipCode(),
                address.getReference(),
                address.getLatitude(),
                address.getLongitude(),
                LocationStatus.of(address),
                address.getLocationSource()
        );
    }
}
