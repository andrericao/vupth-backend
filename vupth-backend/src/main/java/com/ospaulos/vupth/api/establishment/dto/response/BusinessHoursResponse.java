package com.ospaulos.vupth.api.establishment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ospaulos.vupth.domain.establishment.valueObject.BusinessHours;
import com.ospaulos.vupth.domain.establishment.valueObject.DayHours;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Horario por dia (#2). {@code configured=false} (semana vazia) e o sinal para o app pedir ao dono
 * que defina. {@code exceptions} traz so de hoje em diante; {@code upcoming}, os proximos
 * {@value #UPCOMING_DAYS} dias ja resolvidos (excecao, senao o dia da semana).
 */
public record BusinessHoursResponse(
        boolean configured,
        List<Weekday> weekly,
        List<DateHours> exceptions,
        List<Upcoming> upcoming
) {

    public static final int UPCOMING_DAYS = 14;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Weekday(DayOfWeek day, boolean closed, LocalTime opens, LocalTime closes) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DateHours(LocalDate date, boolean closed, LocalTime opens, LocalTime closes) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Upcoming(LocalDate date, DayOfWeek day, boolean closed, LocalTime opens, LocalTime closes,
                           boolean exception) {}

    public static BusinessHoursResponse from(BusinessHours hours, LocalDate today) {
        List<Weekday> weekly = hours.week().stream()
                .map(w -> new Weekday(w.day(), w.hours().closed(), w.hours().opens(), w.hours().closes()))
                .toList();

        List<DateHours> exceptions = hours.exceptionsFrom(today).entrySet().stream()
                .map(e -> new DateHours(e.getKey(), e.getValue().closed(), e.getValue().opens(), e.getValue().closes()))
                .toList();

        List<Upcoming> upcoming = new ArrayList<>();
        if (hours.isConfigured()) {
            for (int i = 0; i < UPCOMING_DAYS; i++) {
                LocalDate date = today.plusDays(i);
                DayHours day = hours.on(date);
                upcoming.add(new Upcoming(date, date.getDayOfWeek(), day.closed(), day.opens(), day.closes(),
                        hours.hasExceptionOn(date)));
            }
        }
        return new BusinessHoursResponse(hours.isConfigured(), weekly, exceptions, upcoming);
    }
}
