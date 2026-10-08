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

    /**
     * O que vale nos dois horarios ao mesmo tempo (agenda-do-profissional: a loja e o profissional).
     * {@code null} e "sem horario" (qualquer hora): devolve o outro. Fechado em um = fechado. 24h em um =
     * o outro. Sem sobreposicao = fechado.
     */
    public static DayHours intersect(DayHours a, DayHours b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.closed || b.closed) return closedDay();
        if (a.isAllDay()) return b;
        if (b.isAllDay()) return a;
        int from = Math.max(a.startMinute(), b.startMinute());
        int to = Math.min(a.endMinute(), b.endMinute());
        if (to <= from) return closedDay();
        return open(LocalTime.ofSecondOfDay((from % MINUTES_PER_DAY) * 60L),
                LocalTime.ofSecondOfDay((to % MINUTES_PER_DAY) * 60L));
    }

    /** {@code inner} cabe inteiro neste horario? Fechado sempre cabe; {@code null} (sem horario) cabe tudo. */
    public boolean contains(DayHours inner) {
        if (inner == null || inner.closed) return true;
        if (closed) return false;
        if (isAllDay()) return true;
        if (inner.isAllDay()) return false;
        return intersect(this, inner).equals(inner);
    }

    private int startMinute() {
        return opens.toSecondOfDay() / 60;
    }

    /** Fim em minutos desde a meia-noite do dia; passa de 1440 quando vira a meia-noite. */
    private int endMinute() {
        int close = closes.toSecondOfDay() / 60;
        return crossesMidnight() ? close + MINUTES_PER_DAY : close;
    }

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
