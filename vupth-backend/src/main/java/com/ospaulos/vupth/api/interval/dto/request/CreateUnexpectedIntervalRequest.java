package com.ospaulos.vupth.api.interval.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.OffsetDateTime;

public record CreateUnexpectedIntervalRequest(
        @NotNull @Positive Integer durationMinutes,
        @NotNull OffsetDateTime startTime,
        String description) {
}
