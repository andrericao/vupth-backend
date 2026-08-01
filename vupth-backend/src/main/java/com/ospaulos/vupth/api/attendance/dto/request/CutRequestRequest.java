package com.ospaulos.vupth.api.attendance.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Corpo de {@code POST /attendances/cut-requests} (BUG5 C2). O cliente vem do JWT — nao entra aqui.
 */
public record CutRequestRequest(
        @NotNull UUID professionalId,
        @NotNull UUID establishmentId,
        @NotEmpty List<UUID> serviceItemIds
) {}
