package com.ospaulos.vupth.api.serviceItem.dto.response;

import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;

import java.math.BigDecimal;
import java.util.UUID;

public record ServiceItemResponse(
        UUID establishmentId,
        String establishmentName,
        UUID id,
        ServiceItemType serviceItemType,
        String customName,
        BigDecimal price,
        Integer weeklyAverageDurationInMinutes
){

    public static ServiceItemResponse fromEntity(ServiceItem item) {
        return new ServiceItemResponse(
                item.getProfessional().getEstablishment().getId(),
                item.getProfessional().getEstablishment().getName(),
                item.getId(),
                item.getServiceItemType(),
                item.getCustomName(),
                item.getPrice(),
                item.getWeeklyAverageDurationInMinutes()
        );
    }
}
