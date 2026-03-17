package com.ospaulos.vupth.domain.interval.valueObject;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum IntervalType {
    RECURRENT("Recurrent fixed interval"),
    UNEXPECTED("Unexpected interval"),
    EMERGENCY("Emergency break"),
    MAINTENANCE("Equipment maintenance");

    private final String description;
}
