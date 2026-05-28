package com.ospaulos.vupth.api.user.dto.response.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosResponse;

public record LoginResponse(

        @JsonProperty("tokens")
        RefreshTokenResponse tokens,

        @JsonProperty("user")
        UserProfileGeneralInfosResponse user
) {
}
