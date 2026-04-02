package com.ospaulos.vupth.api.attendance.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Queue entry request for a guest (no JWT authentication).
 *
 * MVP:
 * - establishmentId and professionalId are required
 * - serviceItemIds is optional (guest may join without selecting a service)
 * - clientName is optional (guest may be anonymous)
 */
public record AttendanceGuestRequest(
        @NotNull(message = "Establishment ID is required")
        UUID establishmentId,

        @NotNull(message = "Professional ID is required")
        UUID professionalId,

        List<UUID> serviceItemIds,

        String clientName
) {
}
