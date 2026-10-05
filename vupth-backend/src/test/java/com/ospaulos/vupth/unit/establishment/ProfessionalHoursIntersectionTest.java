package com.ospaulos.vupth.unit.establishment;

import com.ospaulos.vupth.domain.establishment.valueObject.BusinessHours;
import com.ospaulos.vupth.domain.establishment.valueObject.DayHours;
import com.ospaulos.vupth.domain.establishment.valueObject.WeekdayHours;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests — agenda do profissional (agenda-do-profissional): o que vale de fato e a intersecao do
 * horario da loja com o dele, dia a dia, e a agenda dele so pode caber no horario da loja.
 */
@DisplayName("agenda-do-profissional — intersecao e contencao de horarios")
class ProfessionalHoursIntersectionTest {

    private static final LocalDate MONDAY_21 = LocalDate.of(2026, 9, 21);

    private static DayHours open(String opens, String closes) {
        return DayHours.open(LocalTime.parse(opens), LocalTime.parse(closes));
    }

    private static BusinessHours everyDay(DayHours hours) {
        return BusinessHours.weekly(Arrays.stream(DayOfWeek.values())
                .map(d -> new WeekdayHours(d, hours)).toList());
    }

    @Test
    @DisplayName("intersecao: 08-20 com 09-17 = 09-17; sem sobreposicao = fechado")
    void intersectWindows() {
        assertThat(DayHours.intersect(open("08:00", "20:00"), open("09:00", "17:00"))).isEqualTo(open("09:00", "17:00"));
        assertThat(DayHours.intersect(open("08:00", "12:00"), open("10:00", "18:00"))).isEqualTo(open("10:00", "12:00"));
        assertThat(DayHours.intersect(open("08:00", "12:00"), open("13:00", "18:00"))).isEqualTo(DayHours.closedDay());
    }

    @Test
    @DisplayName("intersecao: fechado ganha; 24h e null (sem horario) devolvem o outro")
    void intersectSpecialCases() {
        assertThat(DayHours.intersect(DayHours.closedDay(), open("09:00", "17:00"))).isEqualTo(DayHours.closedDay());
        assertThat(DayHours.intersect(open("00:00", "00:00"), open("09:00", "17:00"))).isEqualTo(open("09:00", "17:00"));
        assertThat(DayHours.intersect(null, open("09:00", "17:00"))).isEqualTo(open("09:00", "17:00"));
    }

    @Test
    @DisplayName("intersecao com virada de meia-noite: loja 18-02 e profissional 20-01 = 20-01")
    void intersectAcrossMidnight() {
        assertThat(DayHours.intersect(open("18:00", "02:00"), open("20:00", "01:00"))).isEqualTo(open("20:00", "01:00"));
    }

    @Test
    @DisplayName("contencao: so cabe o que fica inteiro dentro; fechado sempre cabe; nada cabe em dia fechado")
    void contains() {
        assertThat(open("08:00", "20:00").contains(open("09:00", "17:00"))).isTrue();
        assertThat(open("08:00", "20:00").contains(open("07:00", "17:00"))).isFalse();
        assertThat(open("08:00", "20:00").contains(DayHours.closedDay())).isTrue();
        assertThat(DayHours.closedDay().contains(open("09:00", "17:00"))).isFalse();
        assertThat(open("00:00", "00:00").contains(open("09:00", "17:00"))).isTrue();
        assertThat(open("08:00", "20:00").contains(open("00:00", "00:00"))).isFalse();
    }

    @Test
    @DisplayName("efetivo: sem agenda propria vale a da loja; loja sem horario vale a dele")
    void effectiveFallbacks() {
        BusinessHours store = everyDay(open("08:00", "20:00"));
        BusinessHours mine = everyDay(open("09:00", "17:00"));

        assertThat(BusinessHours.effective(store, BusinessHours.notConfigured()).on(MONDAY_21)).isEqualTo(open("08:00", "20:00"));
        assertThat(BusinessHours.effective(BusinessHours.notConfigured(), mine).on(MONDAY_21)).isEqualTo(open("09:00", "17:00"));
        assertThat(BusinessHours.effective(BusinessHours.notConfigured(), BusinessHours.notConfigured()).isConfigured()).isFalse();
    }

    @Test
    @DisplayName("efetivo: dia a dia, com as excecoes dos dois lados (folga dele, feriado da loja)")
    void effectiveWithExceptions() {
        BusinessHours store = BusinessHours.of(everyDay(open("08:00", "20:00")).week(),
                Map.of(MONDAY_21.plusDays(1), open("08:00", "12:00")));                // loja: meio expediente na terca
        BusinessHours mine = BusinessHours.of(everyDay(open("09:00", "17:00")).week(),
                Map.of(MONDAY_21, DayHours.closedDay()));                              // ele: folga na segunda

        BusinessHours effective = BusinessHours.effective(store, mine);

        assertThat(effective.on(MONDAY_21)).isEqualTo(DayHours.closedDay());
        assertThat(effective.on(MONDAY_21.plusDays(1))).isEqualTo(open("09:00", "12:00"));
        assertThat(effective.on(MONDAY_21.plusDays(2))).isEqualTo(open("09:00", "17:00"));
        assertThat(effective.isWithin(MONDAY_21.plusDays(2), LocalTime.parse("18:00"))).isFalse();
        assertThat(effective.isWithin(MONDAY_21.plusDays(2), LocalTime.parse("10:00"))).isTrue();
    }

    @Test
    @DisplayName("dia da semana exposto (onWeekday) para a checagem de contencao do caso de uso")
    void onWeekday() {
        BusinessHours store = everyDay(open("08:00", "20:00"));
        assertThat(store.onWeekday(DayOfWeek.MONDAY)).isEqualTo(open("08:00", "20:00"));
        assertThat(BusinessHours.notConfigured().onWeekday(DayOfWeek.MONDAY)).isNull();
        assertThat(List.of(DayOfWeek.values())).allMatch(d -> store.onWeekday(d) != null);
    }
}
