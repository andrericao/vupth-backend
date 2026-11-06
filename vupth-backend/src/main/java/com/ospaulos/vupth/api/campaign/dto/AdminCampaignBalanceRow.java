package com.ospaulos.vupth.api.campaign.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * balanco-por-campanha: os premios de uma campanha no painel, sem os de teste. {@code generated} = {@code locked} (ganho, espera o
 * Andre liberar) + {@code to_pay} (liberado, ainda nao pago) + {@code paid}. {@code credits} conta os premios gerados.
 */
public record AdminCampaignBalanceRow(
        @JsonProperty("campaign_id") UUID campaignId,
        @JsonProperty("name") String name,
        @JsonProperty("type") String type,
        @JsonProperty("credits") long credits,
        @JsonProperty("generated") BigDecimal generated,
        @JsonProperty("locked") BigDecimal locked,
        @JsonProperty("to_pay") BigDecimal toPay,
        @JsonProperty("paid") BigDecimal paid) {
}
