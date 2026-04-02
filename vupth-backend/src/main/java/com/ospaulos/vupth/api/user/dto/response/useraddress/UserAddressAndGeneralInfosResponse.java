package com.ospaulos.vupth.api.user.dto.response.useraddress;

import com.ospaulos.vupth.api.address.dto.response.UserAddressResponse;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosResponse;

public record UserAddressAndGeneralInfosResponse(
        UserProfileGeneralInfosResponse userInfos,
        UserAddressResponse addressResponse
) {
}
