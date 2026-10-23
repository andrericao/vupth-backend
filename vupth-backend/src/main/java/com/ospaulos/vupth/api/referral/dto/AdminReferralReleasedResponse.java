package com.ospaulos.vupth.api.referral.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/** Quantos premios foram liberados para pagamento e quando. */
public record AdminReferralReleasedResponse(
        @JsonProperty("released_credits") int releasedCredits,
        @JsonProperty("released_at") OffsetDateTime releasedAt
) {
}
