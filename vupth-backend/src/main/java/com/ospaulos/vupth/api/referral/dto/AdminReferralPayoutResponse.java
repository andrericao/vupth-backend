package com.ospaulos.vupth.api.referral.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Uma linha do "a pagar" do painel (BAK-9, fatia 3): a campanha, quando paga (pela periodicidade e o dia que
 * o Andre escolheu), a pessoa, a chave Pix dela, o valor em aberto e quantos creditos.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminReferralPayoutResponse(
        @JsonProperty("campaign_id") UUID campaignId,
        @JsonProperty("campaign_name") String campaignName,
        @JsonProperty("payout_period") String payoutPeriod,
        @JsonProperty("payout_day") Integer payoutDay,
        @JsonProperty("next_payout_on") LocalDate nextPayoutOn,
        @JsonProperty("beneficiary_id") UUID beneficiaryId,
        @JsonProperty("beneficiary_name") String beneficiaryName,
        @JsonProperty("pix_key_type") String pixKeyType,
        @JsonProperty("pix_key") String pixKey,
        @JsonProperty("pix_holder_name") String pixHolderName,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("credits") long credits
) {
}
