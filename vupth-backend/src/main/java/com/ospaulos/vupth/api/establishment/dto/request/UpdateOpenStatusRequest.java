package com.ospaulos.vupth.api.establishment.dto.request;

import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateOpenStatusRequest(
        @NotNull(message = "openStatus é obrigatório")
        OpenStatus openStatus
) {}
