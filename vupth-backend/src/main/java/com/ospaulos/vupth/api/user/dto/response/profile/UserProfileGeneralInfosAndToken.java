package com.ospaulos.vupth.api.user.dto.response.profile;

import com.ospaulos.vupth.api.user.dto.response.auth.RefreshTokenResponse;

public record UserProfileGeneralInfosAndToken(

        UserProfileGeneralInfosResponse user,
        RefreshTokenResponse tokens
) {
}
