package com.ospaulos.vupth.api.user.dto.response.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RefreshTokenResponse(

        @JsonProperty("access_token")
        String accessToken,

        @JsonProperty("refresh_token")
        String refreshToken,

        @JsonProperty("expires_in")
        Long expiresIn
) {
}
