package com.ospaulos.vupth.api.referral.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * O link de quem indica (Partner e Client): o codigo, o link para compartilhar, quantos clientes chegaram por ele, quantos ja
 * concluiram o corte com credito e quanto ha a receber (inclui a parte de quem atendeu). Compartilhe e ganhe (DEC-113/115): o
 * codigo vale para todo papel e {@code campaigns} traz as campanhas de link em que a pessoa entrou, cada uma com o seu link, para
 * escolher qual compartilhar (uma so por indicado, nao acumulam). {@code pending_release} e o que ja foi ganho e espera o Andre liberar.
 */
public record ReferralMeResponse(
        @JsonProperty("code") String code,
        @JsonProperty("link") String link,
        @JsonProperty("referred_clients") long referredClients,
        @JsonProperty("credited_clients") long creditedClients,
        @JsonProperty("to_receive") BigDecimal toReceive,
        @JsonProperty("pending_release") BigDecimal pendingRelease,
        @JsonProperty("campaigns") List<CampaignLink> campaigns
) {
    /** O link de uma campanha em que a pessoa entrou, com o que ela ja rendeu. */
    public record CampaignLink(
            @JsonProperty("campaign_id") UUID campaignId,
            @JsonProperty("name") String name,
            @JsonProperty("link") String link,
            @JsonProperty("ends_at") java.time.OffsetDateTime endsAt,
            @JsonProperty("referred_clients") long referredClients,
            @JsonProperty("credited_clients") long creditedClients) {
    }
}
