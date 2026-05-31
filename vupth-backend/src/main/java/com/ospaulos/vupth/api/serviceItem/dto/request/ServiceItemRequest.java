package com.ospaulos.vupth.api.serviceItem.dto.request;

import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;

import java.math.BigDecimal;
import java.util.UUID;

public record ServiceItemRequest(
        UUID establishmentId,
        ServiceItemType serviceItemType,
        String customName,
        BigDecimal price,
        Integer estimatedMinutes
) {
}
