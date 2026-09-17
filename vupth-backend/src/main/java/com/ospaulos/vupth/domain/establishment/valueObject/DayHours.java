package com.ospaulos.vupth.domain.establishment.valueObject;

import com.ospaulos.vupth.domain.establishment.exception.InvalidBusinessHoursException;

import java.time.LocalTime;

/**
 * O horario de UM dia: fechado, ou aberto de {@code opens} ate {@code closes} (hora de parede no fuso
 * da loja). Nunca os dois, nunca nenhum. {@code closes} antes de {@code opens} = vira a meia-noite;
 * {@code opens == closes} = 24h — a mesma semantica do horario unico que existia antes.
 */
public record DayHours(boolean closed, LocalTime opens, LocalTime closes) {

    public DayHours {
        if (closed && (opens != null || closes != null)) {
            throw new InvalidBusinessHoursException("Dia fechado nao leva horario");
        }
        if (!closed && (opens == null || closes == null)) {
            throw new InvalidBusinessHoursException("Dia aberto precisa de horario de abertura e de fechamento");
        }
    }

    public static DayHours closedDay() {
        return new DayHours(true, null, null);
    }

    public static DayHours open(LocalTime opens, LocalTime closes) {
        return new DayHours(false, opens, closes);
    }

    public boolean isAllDay() {
        return !closed && opens.equals(closes);
    }

    public boolean crossesMidnight() {
        return !closed && closes.isBefore(opens);
    }

    /**
     * O intervalo {@code [start, start+minutes)} cabe no expediente deste dia (a refeicao, #11g2)?
     * Na virada da meia-noite vale antes, atravessando ou na madrugada ate fechar; nunca dar a volta
     * no relogio fora do expediente.
     */
    public boolean fits(LocalTime start, int minutes) {
        if (closed) return false;
        if (isAllDay()) return true;
        int from = start.toSecondOfDay() / 60;
        int to = from + minutes;
        int open = opens.toSecondOfDay() / 60;
        int close = closes.toSecondOfDay() / 60;
        if (!crossesMidnight()) {
            return from >= open && to <= close;
        }
        return (from >= open && to <= close + MINUTES_PER_DAY) || (to <= close);
    }

    private static final int MINUTES_PER_DAY = 24 * 60;

    /** A hora {@code t} deste mesmo dia esta dentro? (a madrugada de uma virada e do dia seguinte) */
    boolean coversSameDay(LocalTime t) {
        if (closed) return false;
        if (isAllDay()) return true;
        if (crossesMidnight()) return !t.isBefore(opens);
        return !t.isBefore(opens) && t.isBefore(closes);
    }

    /** A hora {@code t} do dia SEGUINTE ainda esta dentro (madrugada de uma virada)? */
    boolean spillsInto(LocalTime t) {
        return crossesMidnight() && t.isBefore(closes);
    }
}
