package com.ospaulos.vupth.domain.events;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ExtendIntervalCommand(
        @NotNull UUID intervalId,
        @Min(1) @Max(30) Integer additionalMinutes
) {
    public ExtendIntervalCommand(UUID intervalId) {
        this(intervalId, 5);
    }
}