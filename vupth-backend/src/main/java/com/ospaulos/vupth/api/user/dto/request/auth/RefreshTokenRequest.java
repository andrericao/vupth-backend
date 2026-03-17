package com.ospaulos.vupth.api.user.dto.request.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequest(
        @NotBlank(message = "Refresh Token is required!")
        @JsonProperty("refresh_token")
        String refreshToken
) {
}
