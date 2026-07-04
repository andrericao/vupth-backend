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

    /**
     * Edita um ServiceItem pela otica do establishment (owner OU o proprio profissional dono do item).
     * O item tem de pertencer a um profissional afiliado ao establishment informado.
     */
    ServiceItemResponse updateServiceItemForEstablishment(UUID requesterId, UUID establishmentId, UUID itemId, UpdateServiceItemRequest request);

    /**
     * Remove um ServiceItem pela otica do establishment (owner OU o proprio profissional dono do item).
     */
    void deleteServiceItemForEstablishment(UUID requesterId, UUID establishmentId, UUID itemId);

}
