package com.ospaulos.vupth.api.referral.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** O codigo que o Client guardou ao abrir o link de indicacao e, se o link trouxe, a campanha ({@code ?c=}). */
public record ReferralClaimRequest(
        @JsonProperty("code") @NotBlank @Size(max = 12) String code,
        @JsonProperty("campaign_id") UUID campaignId
) {
    public ReferralClaimRequest(String code) {
        this(code, null);
    }
}
