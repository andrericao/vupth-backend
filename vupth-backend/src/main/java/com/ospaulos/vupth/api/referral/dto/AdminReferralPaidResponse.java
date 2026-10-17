package com.ospaulos.vupth.api.referral.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/** Quantos creditos foram marcados como pagos e quando. */
public record AdminReferralPaidResponse(
        @JsonProperty("paid_credits") int paidCredits,
        @JsonProperty("paid_at") OffsetDateTime paidAt
) {
}
