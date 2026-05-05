package com.ospaulos.vupth.api.address.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * DTO de criação de endereço.
 *
 * zipCode: 8 dígitos puros, sem hífen ou ponto (ex: "01310100").
 * O frontend envia apenas os dígitos; a formatação visual fica no front.
 */
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
        @Pattern(
                regexp = "^\\d{8}$",
                message = "Invalid ZIP code. Send only 8 digits, no punctuation (e.g. 01310100)"
        )
        String zipCode,

        String reference,

        Double latitude,

        Double longitude
) {}
