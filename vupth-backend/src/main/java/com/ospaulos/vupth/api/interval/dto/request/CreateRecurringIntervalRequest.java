package com.ospaulos.vupth.api.interval.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.time.LocalTime;

public record CreateRecurringIntervalRequest(
        @NotNull @Positive @Max(value = 240, message = "durationMinutes must be at most 240") Integer durationMinutes,
        @NotNull LocalTime fixedStartTime,
        @NotBlank @Pattern(regexp = "WEEKDAYS|DAILY|WEEKENDS",
                message = "recurrencePattern must be one of: WEEKDAYS, DAILY, WEEKENDS") String recurrencePattern,
        String description) {
}
