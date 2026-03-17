package com.ospaulos.vupth.api.address.dto.response;

import com.ospaulos.vupth.domain.establishment.model.EstablishmentAddress;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record EstablishmentAddressResponse(

        UUID establishmenID,

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

        Double longitude

) {
        public static EstablishmentAddressResponse fromEntity(EstablishmentAddress address) {
                return new EstablishmentAddressResponse(
                        address.getEstablishment().getId(),
                        address.getEstablishment().getName(),
                        address.getAddress().getStreet(),
                        address.getAddress().getNumber(),
                        address.getAddress().getComplement(),
                        address.getAddress().getNeighborhood(),
                        address.getAddress().getCity(),
                        address.getAddress().getState(),
                        address.getAddress().getZipCode(),
                        address.getAddress().getLatitude(),
                        address.getAddress().getLongitude()
                );
        }
}

