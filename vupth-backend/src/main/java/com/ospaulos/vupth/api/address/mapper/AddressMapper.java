package com.ospaulos.vupth.api.address.mapper;

import com.ospaulos.vupth.api.address.dto.request.AddressRequest;
import com.ospaulos.vupth.api.address.dto.response.AddressResponse;
import com.ospaulos.vupth.domain.address.model.Address;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AddressMapper {
    Address toAddressEntity(AddressRequest request);

    AddressResponse toResponse(Address address);
}