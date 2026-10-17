package com.ospaulos.vupth.api.referral.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** O codigo que o Client guardou ao abrir o link de indicacao. */
public record ReferralClaimRequest(
        @JsonProperty("code") @NotBlank @Size(max = 12) String code
) {
}
