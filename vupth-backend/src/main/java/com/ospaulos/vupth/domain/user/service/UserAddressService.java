package com.ospaulos.vupth.domain.user.service;

import com.ospaulos.vupth.api.address.dto.request.AddressRequest;
import com.ospaulos.vupth.api.address.dto.request.UserAddressRequest;
import com.ospaulos.vupth.api.address.dto.response.UserAddressResponse;
import com.ospaulos.vupth.api.user.dto.response.useraddress.UserAddressAndGeneralInfosResponse;

import java.util.UUID;

public interface UserAddressService {

    public UserAddressAndGeneralInfosResponse registerAddress(AddressRequest request, UUID userId);
}
