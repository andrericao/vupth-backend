package com.ospaulos.vupth.api.campaign.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * previa-publica-do-desconto (BAK-1): o que o link de campanha promete para o servico escolhido, sem login. So numeros do
 * servico: a multa nunca entra e nenhum dado de profissional, loja ou indicador sai daqui.
 */
public record CampaignPreviewResponse(
        @JsonProperty("full_price") BigDecimal fullPrice,
        BigDecimal discount,
        @JsonProperty("price_with_coupon") BigDecimal priceWithCoupon,
        @JsonProperty("benefit_base") String benefitBase) {
}
