package com.ospaulos.vupth.api.serviceItem.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;

import java.math.BigDecimal;
import java.util.UUID;

public record ServiceItemRequest(
        UUID establishmentId,
        ServiceItemType serviceItemType,
        String customName,
        BigDecimal price,
        Integer estimatedMinutes,

        // Total pago pelo cliente (preco + Taxas Vupth), calculado no front.
        @JsonProperty("client_price")
        BigDecimal clientPrice,

        // Se o servico usa a precificacao da plataforma (absorcao das Taxas Vupth).
        @JsonProperty("uses_platform_pricing")
        Boolean usesPlatformPricing,

        // "Voce recebe": o que o profissional recebe. Opcional ate o app enviar; com ele, o preco
        // passa pela politica de preco (PriceSplit) e o backend calcula a divisao da taxa.
        @JsonProperty("pro_receives")
        BigDecimal proReceives
) {
    /** Sem {@code pro_receives} (servico legado). */
    public ServiceItemRequest(UUID establishmentId, ServiceItemType serviceItemType, String customName,
                              BigDecimal price, Integer estimatedMinutes, BigDecimal clientPrice,
                              Boolean usesPlatformPricing) {
        this(establishmentId, serviceItemType, customName, price, estimatedMinutes, clientPrice,
                usesPlatformPricing, null);
    }
}
