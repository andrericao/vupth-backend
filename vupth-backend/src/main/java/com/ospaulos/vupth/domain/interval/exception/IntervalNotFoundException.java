package com.ospaulos.vupth.domain.interval.exception;

import java.util.UUID;

public class IntervalNotFoundException extends RuntimeException {
    public IntervalNotFoundException(UUID intervalId) {
        super("Interval not found: " + intervalId);
    }
}
