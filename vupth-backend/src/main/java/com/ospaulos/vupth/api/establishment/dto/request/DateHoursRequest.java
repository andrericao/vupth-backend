package com.ospaulos.vupth.api.establishment.dto.request;

import com.ospaulos.vupth.domain.establishment.valueObject.DayHours;

import java.time.LocalTime;

/** PUT /establishment/{id}/hours/dates/{data} — horario de uma data, ou {@code closed: true}. */
public record DateHoursRequest(Boolean closed, LocalTime opens, LocalTime closes) {

    public DayHours toDayHours() {
        return new DayHours(Boolean.TRUE.equals(closed), opens, closes);
    }
}
