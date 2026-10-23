package com.ospaulos.vupth.api.campaign.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * O funil de uma campanha no painel (admin-funil-da-campanha), so agregado. {@code link_opened} e {@code null} e
 * {@code link_opened_measured} e false: o clique no link ainda nao e gravado (vem com o "Compartilhe e ganhe"). {@code accounts_created}
 * conta as contas criadas por indicacao na janela; {@code first_cut_done}, os clientes indicados que concluiram um corte e geraram
 * bonificacao desta campanha; {@code redeemed}, os cupons aceitos. {@code redeemers} traz so o nome e a data.
 */
public record AdminCampaignFunnel(
        @JsonProperty("campaign_id") UUID campaignId,
        @JsonProperty("name") String name,
        @JsonProperty("type") String type,
        @JsonProperty("from") OffsetDateTime from,
        @JsonProperty("to") OffsetDateTime to,
        @JsonProperty("link_opened") Long linkOpened,
        @JsonProperty("link_opened_measured") boolean linkOpenedMeasured,
        @JsonProperty("accounts_created") long accountsCreated,
        @JsonProperty("first_cut_done") long firstCutDone,
        @JsonProperty("redeemed") long redeemed,
        @JsonProperty("redeemers") List<Redeemer> redeemers
) {
    public record Redeemer(
            @JsonProperty("name") String name,
            @JsonProperty("redeemed_at") OffsetDateTime redeemedAt
    ) {
    }
}
