package com.ospaulos.vupth.domain.establishment.valueObject;

import com.ospaulos.vupth.domain.establishment.exception.InvalidBusinessHoursException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Horario de funcionamento da loja por dia (#2 — modelo do Andre, 16/09).
 *
 * <ul>
 *   <li>A semana tem os 7 dias, cada um com horario ou fechado ({@link DayHours}).</li>
 *   <li>Uma excecao vale para UMA data e ganha do dia da semana; tambem pode ser "fechado".</li>
 *   <li>O horario de uma data e a excecao dela, senao o dia da semana: nenhuma data fica sem definicao.</li>
 *   <li>O passado e documento: excecao so se cria ou remove de hoje em diante.</li>
 *   <li>Trocar a semana mantem as excecoes ja marcadas.</li>
 * </ul>
 * Loja que ainda nao definiu fica "nao configurada" (semana vazia): nenhum horario e inventado, e
 * nesse estado qualquer hora vale — o comportamento de hoje de loja sem horario.
 * Imutavel: toda mudanca devolve uma nova instancia.
 */
public final class BusinessHours {

    private final Map<DayOfWeek, DayHours> week;
    private final TreeMap<LocalDate, DayHours> exceptions;

    private BusinessHours(Map<DayOfWeek, DayHours> week, Map<LocalDate, DayHours> exceptions) {
        Map<DayOfWeek, DayHours> copy = new EnumMap<>(DayOfWeek.class);
        copy.putAll(week);
        this.week = Collections.unmodifiableMap(copy);
        this.exceptions = new TreeMap<>(exceptions);
    }

    public static BusinessHours notConfigured() {
        return new BusinessHours(new EnumMap<>(DayOfWeek.class), Map.of());
    }

    public static BusinessHours weekly(List<WeekdayHours> days) {
        return new BusinessHours(fullWeek(days), Map.of());
    }

    /** O horario unico antigo aplicado aos 7 dias. */
    public static BusinessHours everyDay(DayHours hours) {
        Map<DayOfWeek, DayHours> week = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            week.put(day, hours);
        }
        return new BusinessHours(week, Map.of());
    }

    /**
     * Reconstroi o que ja esta gravado (semana vazia = nao configurada; excecoes passadas incluidas,
     * sao documento). Sem a checagem de data: quem grava ja passou por ela.
     */
    public static BusinessHours of(Collection<WeekdayHours> days, Map<LocalDate, DayHours> exceptions) {
        Map<DayOfWeek, DayHours> week = days == null || days.isEmpty()
                ? new EnumMap<>(DayOfWeek.class)
                : fullWeek(List.copyOf(days));
        return new BusinessHours(week, exceptions == null ? Map.of() : exceptions);
    }

    public BusinessHours withWeek(List<WeekdayHours> days) {
        return new BusinessHours(fullWeek(days), exceptions);
    }

    public BusinessHours withException(LocalDate date, DayHours hours, LocalDate today) {
        requireConfigured();
        requireNotPast(date, today);
        if (hours == null) {
            throw new InvalidBusinessHoursException("A excecao precisa de horario ou de fechado");
        }
        Map<LocalDate, DayHours> next = new TreeMap<>(exceptions);
        next.put(date, hours);
        return new BusinessHours(week, next);
    }

    public BusinessHours withoutException(LocalDate date, LocalDate today) {
        requireNotPast(date, today);
        Map<LocalDate, DayHours> next = new TreeMap<>(exceptions);
        next.remove(date);
        return new BusinessHours(week, next);
    }

    public boolean isConfigured() {
        return !week.isEmpty();
    }

    /** O horario da data (excecao, senao o dia da semana), ou {@code null} se a loja nao configurou. */
    public DayHours on(LocalDate date) {
        if (!isConfigured()) {
            return null;
        }
        DayHours exception = exceptions.get(date);
        return exception != null ? exception : week.get(date.getDayOfWeek());
    }

    public boolean hasExceptionOn(LocalDate date) {
        return exceptions.containsKey(date);
    }

    /**
     * A hora de parede {@code time} da data {@code date} esta dentro do horario? Considera a madrugada
     * de uma virada de meia-noite aberta na vespera. Nao configurada = sempre dentro.
     */
    public boolean isWithin(LocalDate date, LocalTime time) {
        if (!isConfigured()) {
            return true;
        }
        return on(date).coversSameDay(time) || on(date.minusDays(1)).spillsInto(time);
    }

    /** A semana na ordem de segunda a domingo (vazia se nao configurada). */
    public List<WeekdayHours> week() {
        return week.entrySet().stream().map(e -> new WeekdayHours(e.getKey(), e.getValue())).toList();
    }

    /** Excecoes de {@code from} em diante, em ordem de data. */
    public Map<LocalDate, DayHours> exceptionsFrom(LocalDate from) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(exceptions.tailMap(from, true)));
    }

    /** Todas as excecoes gravadas, passadas incluidas (para persistir). */
    public Map<LocalDate, DayHours> allExceptions() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(exceptions));
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new InvalidBusinessHoursException(
                    "Defina primeiro o horario da semana; so depois as excecoes por data");
        }
    }

    private static void requireNotPast(LocalDate date, LocalDate today) {
        if (date == null) {
            throw new InvalidBusinessHoursException("A excecao precisa de uma data");
        }
        if (date.isBefore(today)) {
            throw new InvalidBusinessHoursException(
                    "O passado e documento: nao se altera o horario de " + date);
        }
    }

    private static Map<DayOfWeek, DayHours> fullWeek(List<WeekdayHours> days) {
        if (days == null) {
            throw new InvalidBusinessHoursException("A semana precisa dos 7 dias");
        }
        Map<DayOfWeek, DayHours> week = new EnumMap<>(DayOfWeek.class);
        for (WeekdayHours entry : days) {
            if (entry == null || entry.day() == null || entry.hours() == null) {
                throw new InvalidBusinessHoursException("Cada dia da semana precisa do nome e do horario (ou fechado)");
            }
            if (week.put(entry.day(), entry.hours()) != null) {
                throw new InvalidBusinessHoursException("Dia repetido na semana: " + entry.day());
            }
        }
        if (week.size() != DayOfWeek.values().length) {
            throw new InvalidBusinessHoursException(
                    "A semana precisa dos 7 dias — vieram " + week.size());
        }
        return week;
    }
}
