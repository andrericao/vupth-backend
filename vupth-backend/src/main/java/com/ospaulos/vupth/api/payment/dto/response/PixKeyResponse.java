package com.ospaulos.vupth.api.payment.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.payment.PixKeyType;

/** A chave Pix do profissional (pagamento-pix). */
public record PixKeyResponse(
        @JsonProperty("key_type") PixKeyType keyType,
        @JsonProperty("key") String key,
        @JsonProperty("holder_name") String holderName
) {
}
