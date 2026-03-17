package com.ospaulos.vupth.api.address.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AddressRequest(
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

        Double longitude
) {
}
