package com.ospaulos.vupth.api.referral.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** O link de quem indica (Partner): o codigo, o link para compartilhar e quantos clientes chegaram por ele. */
public record ReferralMeResponse(
        @JsonProperty("code") String code,
        @JsonProperty("link") String link,
        @JsonProperty("referred_clients") long referredClients
) {
}
