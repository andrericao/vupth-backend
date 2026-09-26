package com.ospaulos.vupth.api.professional.dto.request;

import com.ospaulos.vupth.domain.professional.valueObject.OperationMode;
import jakarta.validation.constraints.NotNull;

/** Corpo do {@code PATCH /professional/me/operation-mode} — {@code QUEUE} ou {@code AGENDA_ONLY}. */
public record UpdateOperationModeRequest(
        @NotNull(message = "mode is required") OperationMode mode
) {}
