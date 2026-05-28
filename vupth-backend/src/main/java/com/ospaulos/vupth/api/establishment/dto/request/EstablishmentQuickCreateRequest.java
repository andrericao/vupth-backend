package com.ospaulos.vupth.api.establishment.dto.request;

import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record EstablishmentQuickCreateRequest(
        @NotBlank(message = "Establishment name is required") String name,
        @NotNull(message = "Service type is required") ServiceType serviceType
) {}
