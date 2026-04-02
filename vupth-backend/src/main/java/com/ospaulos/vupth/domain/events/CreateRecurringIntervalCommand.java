package com.ospaulos.vupth.domain.events;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalTime;
import java.time.OffsetTime;
import java.util.UUID;

public record CreateRecurringIntervalCommand(
        @NotNull UUID professionalId,
        @NotNull @Positive Integer durationMinutes,
        @NotNull OffsetTime fixedStartTime,
        @NotBlank String recurrencePattern,
        String description
) {
}
