package com.ospaulos.vupth.domain.events;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalTime;
import java.util.UUID;

public record CreateRecurringIntervalCommand(
        @NotNull UUID professionalId,
        @NotNull @Positive Integer durationMinutes,
        @NotNull LocalTime fixedStartTime,
        @NotBlank String recurrencePattern,
        String description
) {
}
