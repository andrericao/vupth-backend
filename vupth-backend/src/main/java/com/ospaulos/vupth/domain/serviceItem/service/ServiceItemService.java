package com.ospaulos.vupth.domain.serviceItem.service;

import com.ospaulos.vupth.api.serviceItem.dto.request.ServiceItemRequest;
import com.ospaulos.vupth.api.serviceItem.dto.request.UpdateServiceItemRequest;
import com.ospaulos.vupth.api.serviceItem.dto.response.ServiceItemResponse;
import com.ospaulos.vupth.domain.professional.model.Professional;

import java.util.UUID;

public interface ServiceItemService {

    public ServiceItemResponse createServiceItem(Professional professional, ServiceItemRequest request);

    ServiceItemResponse updateServiceItem(UUID requesterId, UUID professionalId, UUID itemId, UpdateServiceItemRequest request);

    void deleteServiceItem(UUID requesterId, UUID professionalId, UUID itemId);

}
