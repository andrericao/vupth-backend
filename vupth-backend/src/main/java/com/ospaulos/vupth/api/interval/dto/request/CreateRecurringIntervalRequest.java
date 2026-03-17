package com.ospaulos.vupth.api.interval.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.OffsetTime;

public record CreateRecurringIntervalRequest(
        @NotNull @Positive Integer durationMinutes,
        @NotNull OffsetTime fixedStartTime,
        @NotBlank String recurrencePattern,
        String description) {
}
