package com.ospaulos.vupth.api.referral.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * O link de quem indica (Partner): o codigo, o link para compartilhar, quantos clientes chegaram por ele,
 * quantos ja concluiram o corte com credito e quanto ha a receber (inclui a parte de quem atendeu).
 */
public record ReferralMeResponse(
        @JsonProperty("code") String code,
        @JsonProperty("link") String link,
        @JsonProperty("referred_clients") long referredClients,
        @JsonProperty("credited_clients") long creditedClients,
        @JsonProperty("to_receive") BigDecimal toReceive
) {
}
