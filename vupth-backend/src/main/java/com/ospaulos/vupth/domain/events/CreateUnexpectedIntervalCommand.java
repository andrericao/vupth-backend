package com.ospaulos.vupth.domain.events;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.UUID;

public record CreateUnexpectedIntervalCommand(
        @NotNull UUID professionalId,
        @NotNull @Positive Integer durationMinutes,
        @NotNull @Future OffsetDateTime startTime,
        String description
) {
}
