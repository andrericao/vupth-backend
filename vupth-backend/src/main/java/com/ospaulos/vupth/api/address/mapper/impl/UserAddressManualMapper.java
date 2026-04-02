package com.ospaulos.vupth.api.address.mapper.impl;

import com.ospaulos.vupth.api.address.dto.response.UserAddressResponse;
import com.ospaulos.vupth.domain.address.model.Address;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.UserAddress;
import org.springframework.stereotype.Component;

@Component
public class UserAddressManualMapper {

    public UserAddressResponse toUserAddressResponse(UserAddress userAddress){
        Address address = userAddress.getAddress();
        BaseUser user = userAddress.getBaseUser();

        return new UserAddressResponse(
                user.getId(),
                userAddress.getId(),
                userAddress.getIsMain(),
                user.getEmail(),
                address.getStreet(),
                address.getNumber(),
                address.getComplement(),
                address.getNeighborhood(),
                address.getCity(),
                address.getState(),
                address.getZipCode(),
                address.getReference(),
                address.getLatitude(),
                address.getLongitude()
        );
    }
}
