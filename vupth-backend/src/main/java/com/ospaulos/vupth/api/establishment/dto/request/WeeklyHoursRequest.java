package com.ospaulos.vupth.api.establishment.dto.request;

import com.ospaulos.vupth.domain.establishment.valueObject.DayHours;
import com.ospaulos.vupth.domain.establishment.valueObject.WeekdayHours;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

/**
 * PUT /establishment/{id}/hours/weekly — a semana INTEIRA (os 7 dias). Marcar varios dias e definir o
 * horario de uma vez e coisa da tela; aqui cada dia vem na sua entrada. A validacao (7 dias, sem
 * repeticao, horario XOR fechado) e do dominio ({@code BusinessHours}).
 */
public record WeeklyHoursRequest(List<Day> days) {

    public record Day(DayOfWeek day, Boolean closed, LocalTime opens, LocalTime closes) {
        WeekdayHours toWeekdayHours() {
            return new WeekdayHours(day, new DayHours(Boolean.TRUE.equals(closed), opens, closes));
        }
    }

    public List<WeekdayHours> toWeek() {
        return days == null ? null : days.stream().map(d -> d == null ? null : d.toWeekdayHours()).toList();
    }
}
