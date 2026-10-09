package com.ospaulos.vupth.api.user.dto.response.profile;

import com.ospaulos.vupth.api.user.dto.response.auth.RefreshTokenResponse;

public record UserProfileGeneralInfosAndToken(

        UserProfileGeneralInfosResponse user,
        RefreshTokenResponse tokens,
        // desativar-conta: true no login de conta desativada — o app oferece "Reativar" ou "So consultar"
        boolean deactivated
) {
    public UserProfileGeneralInfosAndToken(UserProfileGeneralInfosResponse user, RefreshTokenResponse tokens) {
        this(user, tokens, false);
    }

}
