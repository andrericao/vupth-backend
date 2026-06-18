package com.ospaulos.vupth.api.interval.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.OffsetDateTime;

public record CreateUnexpectedIntervalRequest(
        @NotNull @Positive @Max(value = 240, message = "durationMinutes must be at most 240") Integer durationMinutes,
        @NotNull OffsetDateTime startTime,
        String description) {
}
