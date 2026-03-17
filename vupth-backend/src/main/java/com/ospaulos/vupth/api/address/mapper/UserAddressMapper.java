package com.ospaulos.vupth.api.address.mapper;

import com.ospaulos.vupth.api.address.dto.response.UserAddressResponse;
import com.ospaulos.vupth.domain.address.model.Address;
import org.mapstruct.Mapper;

import java.util.UUID;

@Mapper(componentModel = "spring")
public interface UserAddressMapper {
    UserAddressResponse toUserAddressResponse(UUID userId, UUID userAddressId, Boolean isMain, Address address);
}
