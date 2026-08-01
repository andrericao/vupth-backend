package com.ospaulos.vupth.api.professional.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * Corpo do {@code PATCH /professional/me/accepting-new} (S1 fatia 3 — "fechar fila").
 * {@code value=false} fecha a fila para novos clientes (drenagem); {@code value=true} reabre.
 */
public record UpdateAcceptingNewRequest(
        @NotNull(message = "value is required") Boolean value
) {}
