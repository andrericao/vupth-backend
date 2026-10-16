package com.ospaulos.vupth.api.referral.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/** O cliente ficou ligado a quem o indicou, desde {@code referred_at}. */
public record ReferralClaimResponse(
        @JsonProperty("code") String code,
        @JsonProperty("referred_at") OffsetDateTime referredAt
) {
}
