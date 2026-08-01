package com.ospaulos.vupth.domain.establishment.service;

import com.ospaulos.vupth.api.address.dto.request.AddressRequest;
import com.ospaulos.vupth.api.address.dto.request.UpdateEstablishmentAddressRequest;
import com.ospaulos.vupth.api.address.dto.response.EstablishmentAddressResponse;

import java.util.UUID;

public interface EstablishmentAddressService {

    EstablishmentAddressResponse registerAddress(AddressRequest request, UUID establishmentId, UUID ownerId);

    EstablishmentAddressResponse updateAddress(UpdateEstablishmentAddressRequest request,
                                               UUID establishmentId,
                                               UUID addressId,
                                               UUID ownerId);
}
