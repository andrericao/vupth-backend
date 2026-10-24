package com.ospaulos.vupth.api.referral.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * O cliente ficou ligado a quem o indicou, desde {@code referred_at}. {@code campaign_id} e a campanha de link que vai render o
 * premio; nulo = quem indicou nao estava em nenhuma campanha: o registro vale para medir, mas nao paga.
 */
public record ReferralClaimResponse(
        @JsonProperty("code") String code,
        @JsonProperty("referred_at") OffsetDateTime referredAt,
        @JsonProperty("campaign_id") UUID campaignId
) {
    public ReferralClaimResponse(String code, OffsetDateTime referredAt) {
        this(code, referredAt, null);
    }
}
