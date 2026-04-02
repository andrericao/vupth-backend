package com.ospaulos.vupth.domain.events;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record FinishIntervalCommand(
        @NotNull UUID intervalId,
        String reason
) {
    public static FinishIntervalCommand attendNext(UUID intervalId) {
        return new FinishIntervalCommand(intervalId, "ATTEND_NEXT_CLIENT");
    }

    public static FinishIntervalCommand emergency(UUID intervalId) {
        return new FinishIntervalCommand(intervalId, "EMERGENCY");
    }

    // TODO: no reason needed — just finish the interval
}
