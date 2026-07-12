package com.ospaulos.vupth.api.serviceItem.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
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

        @JsonProperty("client_price")
        BigDecimal clientPrice,

        @JsonProperty("uses_platform_pricing")
        Boolean usesPlatformPricing,

        Integer weeklyAverageDurationInMinutes,

        // Rating denormalizado por servico (item #6). Media (2 casas) null quando sem votos.
        @JsonProperty("rating_average")
        Double ratingAverage,

        @JsonProperty("rating_count")
        long ratingCount
){

    public static ServiceItemResponse fromEntity(ServiceItem item) {
        return new ServiceItemResponse(
                item.getProfessional().getEstablishment().getId(),
                item.getProfessional().getEstablishment().getName(),
                item.getId(),
                item.getServiceItemType(),
                item.getCustomName(),
                item.getPrice(),
                item.getClientPrice(),
                item.getUsesPlatformPricing(),
                item.getWeeklyAverageDurationInMinutes(),
                item.getRatingAverage(),
                item.getRatingCount()
        );
    }
}
