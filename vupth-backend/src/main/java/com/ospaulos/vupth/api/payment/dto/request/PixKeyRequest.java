package com.ospaulos.vupth.api.payment.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.payment.PixKeyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** PUT /professional/me/pix (pagamento-pix). */
public record PixKeyRequest(
        @NotNull @JsonProperty("key_type") PixKeyType keyType,
        @NotBlank @Size(max = 77) @JsonProperty("key") String key,
        @NotBlank @Size(max = 25) @JsonProperty("holder_name") String holderName
) {
}
