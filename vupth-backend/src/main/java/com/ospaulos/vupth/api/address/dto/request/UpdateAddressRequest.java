package com.ospaulos.vupth.api.address.dto.request;

import com.ospaulos.vupth.domain.address.valueObject.AddressType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record UpdateAddressRequest(
        // TODO: Criar - olhar no chat gpt
        //  pela conversa aberta

        @NotNull
        UUID userAddressId,

        Boolean isMain,
        AddressType type,

        String street,

        String number,

        String complement,

        String neighborhood,

        String city,

        String state,

        @Pattern(regexp = "\\d{2}.\\d{3}-\\d{3}", message = "Invalid ZIP code format")
        String zipCode,

        String reference,

        Double latitude,

        Double longitude
) {
}
