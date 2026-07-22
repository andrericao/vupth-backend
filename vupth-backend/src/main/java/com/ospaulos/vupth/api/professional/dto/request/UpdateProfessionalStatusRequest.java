package com.ospaulos.vupth.api.professional.dto.request;

import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Corpo do {@code PATCH /professional/me/status} (S1). O profissional muda o proprio status de
 * trabalho: {@code WORKING} (trabalhando), {@code ON_BREAK} (intervalo) ou {@code OFF} (indisponivel).
 */
public record UpdateProfessionalStatusRequest(
        @NotNull(message = "status is required") ProfessionalStatus status
) {}
