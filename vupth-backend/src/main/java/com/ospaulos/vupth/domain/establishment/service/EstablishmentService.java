package com.ospaulos.vupth.domain.establishment.service;

import com.ospaulos.vupth.api.establishment.dto.request.EstablishmentRequest;
import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentResponse;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;

import java.util.List;
import java.util.UUID;

public interface EstablishmentService {

    EstablishmentResponse registerEstablishment(EstablishmentRequest request);

    EstablishmentResponse findById(UUID id);

    List<EstablishmentResponse> findAll();

    public List<ServiceItem> getServicesItemByEstablishment(UUID establishmentId);
}
