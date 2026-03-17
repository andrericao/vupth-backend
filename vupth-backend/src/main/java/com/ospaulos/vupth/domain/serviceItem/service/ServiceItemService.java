package com.ospaulos.vupth.domain.serviceItem.service;

import com.ospaulos.vupth.api.serviceItem.dto.request.ServiceItemRequest;
import com.ospaulos.vupth.api.serviceItem.dto.response.ServiceItemResponse;
import com.ospaulos.vupth.domain.professional.model.Professional;

public interface ServiceItemService {

    public ServiceItemResponse createServiceItem(Professional professional, ServiceItemRequest request);

}
