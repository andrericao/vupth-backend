package com.ospaulos.vupth.domain.establishment.valueObject;

import java.time.DayOfWeek;

/** Uma entrada da semana: o dia e o seu horario. */
public record WeekdayHours(DayOfWeek day, DayHours hours) {
}
