package com.ospaulos.vupth.domain.establishment.service;

import com.ospaulos.vupth.api.establishment.dto.request.EstablishmentRequest;
import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentResponse;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;

import java.util.List;
import java.util.UUID;

public interface EstablishmentService {

    EstablishmentResponse registerEstablishment(EstablishmentRequest request);

    EstablishmentResponse findById(UUID id);

    List<EstablishmentResponse> findAll();

    List<EstablishmentResponse> findAllActive();

    List<EstablishmentResponse> searchByName(String name);

    List<EstablishmentResponse> findByServiceType(ServiceType serviceType);

    List<EstablishmentResponse> searchByNeighborhood(String neighborhood);

    List<EstablishmentResponse> searchByServiceTypeAndNeighborhood(ServiceType serviceType, String neighborhood);

    EstablishmentResponse updateOpenStatus(UUID establishmentId, UUID ownerId, OpenStatus newStatus);

    List<ServiceItem> getServicesItemByEstablishment(UUID establishmentId);
}
