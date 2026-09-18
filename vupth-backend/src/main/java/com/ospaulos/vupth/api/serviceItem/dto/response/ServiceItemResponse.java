package com.ospaulos.vupth.api.serviceItem.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.serviceItem.valueObject.PriceSplit;
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
        long ratingCount,

        // Divisao do preco (Receita LIQUIDA). As tres sao null em servico legado — o app deve pedir
        // ao profissional que atualize o preco desse servico.
        @JsonProperty("pro_receives")
        BigDecimal proReceives,

        @JsonProperty("base_price")
        BigDecimal basePrice,

        @JsonProperty("intermediary_fee")
        BigDecimal intermediaryFee,

        @JsonProperty("platform_fee")
        BigDecimal platformFee
){

    /** Sem a divisao do preco (servico legado). */
    public ServiceItemResponse(UUID establishmentId, String establishmentName, UUID id,
                               ServiceItemType serviceItemType, String customName, BigDecimal price,
                               BigDecimal clientPrice, Boolean usesPlatformPricing,
                               Integer weeklyAverageDurationInMinutes, Double ratingAverage, long ratingCount) {
        this(establishmentId, establishmentName, id, serviceItemType, customName, price, clientPrice,
                usesPlatformPricing, weeklyAverageDurationInMinutes, ratingAverage, ratingCount, null, null, null, null);
    }

    public static ServiceItemResponse fromEntity(ServiceItem item) {
        PriceSplit split = item.getPriceSplit();
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
                item.getRatingCount(),
                split != null ? split.proReceives() : null,
                split != null ? split.basePrice() : null,
                split != null ? split.intermediaryFee() : null,
                split != null ? split.platformFee() : null
        );
    }
}
