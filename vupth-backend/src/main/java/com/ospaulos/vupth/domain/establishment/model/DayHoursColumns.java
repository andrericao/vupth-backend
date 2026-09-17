package com.ospaulos.vupth.domain.establishment.model;

import com.ospaulos.vupth.domain.establishment.valueObject.DayHours;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/** Forma gravada de um {@link DayHours} (uma linha da semana ou de uma excecao por data). */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DayHoursColumns {

    @Column(name = "closed", nullable = false)
    private boolean closed;

    @Column(name = "opens")
    private LocalTime opens;

    @Column(name = "closes")
    private LocalTime closes;

    static DayHoursColumns from(DayHours hours) {
        DayHoursColumns columns = new DayHoursColumns();
        columns.closed = hours.closed();
        columns.opens = hours.opens();
        columns.closes = hours.closes();
        return columns;
    }

    DayHours toDayHours() {
        return new DayHours(closed, opens, closes);
    }
}
