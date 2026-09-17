package com.ospaulos.vupth.unit.establishment;

import com.ospaulos.vupth.domain.establishment.exception.InvalidBusinessHoursException;
import com.ospaulos.vupth.domain.establishment.valueObject.BusinessHours;
import com.ospaulos.vupth.domain.establishment.valueObject.DayHours;
import com.ospaulos.vupth.domain.establishment.valueObject.WeekdayHours;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests — horario de funcionamento por dia (#2, modelo do Andre de 16/09).
 *
 * Invariantes:
 * - a semana tem os 7 dias, cada um com horario OU fechado (nunca os dois, nunca nenhum);
 * - a excecao vale para uma data e ganha do dia da semana;
 * - nenhuma data fica sem definicao;
 * - o passado e documento: excecao so de hoje em diante.
 * Semantica herdada do horario unico: fecha antes de abrir = vira a meia-noite; abre == fecha = 24h.
 */
@DisplayName("BusinessHours — horario por dia")
class BusinessHoursTest {

    private static final LocalDate MONDAY_21 = LocalDate.of(2026, 9, 21);   // segunda
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 17);       // quinta

    private static LocalTime t(String hhmm) {
        return LocalTime.parse(hhmm);
    }

    private static DayHours open(String opens, String closes) {
        return DayHours.open(t(opens), t(closes));
    }

    /** seg-sex 08-18, sab 08-12, dom fechado. */
    private static List<WeekdayHours> regularWeek() {
        List<WeekdayHours> week = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            DayHours hours = switch (day) {
                case SATURDAY -> open("08:00", "12:00");
                case SUNDAY -> DayHours.closedDay();
                default -> open("08:00", "18:00");
            };
            week.add(new WeekdayHours(day, hours));
        }
        return week;
    }

    private static BusinessHours regular() {
        return BusinessHours.weekly(regularWeek());
    }

    @Nested
    @DisplayName("um dia: horario ou fechado")
    class Day {

        @Test
        @DisplayName("fechado nao leva horario; aberto exige os dois")
        void closedXorHours() {
            assertThat(DayHours.closedDay().closed()).isTrue();
            assertThatThrownBy(() -> new DayHours(true, t("08:00"), t("18:00")))
                    .isInstanceOf(InvalidBusinessHoursException.class);
            assertThatThrownBy(() -> new DayHours(false, null, t("18:00")))
                    .isInstanceOf(InvalidBusinessHoursException.class);
            assertThatThrownBy(() -> new DayHours(false, t("08:00"), null))
                    .isInstanceOf(InvalidBusinessHoursException.class);
        }

        @Test
        @DisplayName("fecha antes de abrir vira a meia-noite; abre == fecha e 24h")
        void overnightAndAllDay() {
            assertThat(open("22:00", "02:00").crossesMidnight()).isTrue();
            assertThat(open("08:00", "18:00").crossesMidnight()).isFalse();
            assertThat(open("00:00", "00:00").isAllDay()).isTrue();
        }
    }

    @Nested
    @DisplayName("a semana: os 7 dias, sem repeticao")
    class Week {

        @Test
        @DisplayName("semana completa e aceita, e cada dia devolve o seu horario")
        void fullWeek() {
            BusinessHours hours = regular();

            assertThat(hours.isConfigured()).isTrue();
            assertThat(hours.on(MONDAY_21)).isEqualTo(open("08:00", "18:00"));
            assertThat(hours.on(MONDAY_21.plusDays(5))).isEqualTo(open("08:00", "12:00"));
            assertThat(hours.on(MONDAY_21.plusDays(6)).closed()).isTrue();
        }

        @Test
        @DisplayName("faltando um dia: recusada")
        void missingDay() {
            List<WeekdayHours> week = regularWeek();
            week.remove(6);
            assertThatThrownBy(() -> BusinessHours.weekly(week)).isInstanceOf(InvalidBusinessHoursException.class);
        }

        @Test
        @DisplayName("dia repetido (mesmo com 7 entradas): recusada")
        void duplicatedDay() {
            List<WeekdayHours> week = regularWeek();
            week.set(6, new WeekdayHours(DayOfWeek.MONDAY, open("09:00", "17:00")));
            assertThatThrownBy(() -> BusinessHours.weekly(week)).isInstanceOf(InvalidBusinessHoursException.class);
        }

        @Test
        @DisplayName("dia sem nome ou sem definicao: recusada")
        void nullEntries() {
            List<WeekdayHours> week = regularWeek();
            week.set(0, new WeekdayHours(null, open("08:00", "18:00")));
            assertThatThrownBy(() -> BusinessHours.weekly(week)).isInstanceOf(InvalidBusinessHoursException.class);

            List<WeekdayHours> week2 = regularWeek();
            week2.set(0, new WeekdayHours(DayOfWeek.MONDAY, null));
            assertThatThrownBy(() -> BusinessHours.weekly(week2)).isInstanceOf(InvalidBusinessHoursException.class);

            assertThatThrownBy(() -> BusinessHours.weekly(null)).isInstanceOf(InvalidBusinessHoursException.class);
        }

        @Test
        @DisplayName("loja que nao definiu: nao configurada, sem horario para data nenhuma")
        void notConfigured() {
            BusinessHours hours = BusinessHours.notConfigured();

            assertThat(hours.isConfigured()).isFalse();
            assertThat(hours.on(MONDAY_21)).isNull();
        }

        @Test
        @DisplayName("horario unico antigo vira o mesmo horario nos 7 dias")
        void fromSingleWindow() {
            BusinessHours hours = BusinessHours.everyDay(open("09:00", "19:00"));

            for (int i = 0; i < 7; i++) {
                assertThat(hours.on(MONDAY_21.plusDays(i))).isEqualTo(open("09:00", "19:00"));
            }
        }
    }

    @Nested
    @DisplayName("excecao por data")
    class Exceptions {

        @Test
        @DisplayName("a excecao ganha do dia da semana, so naquela data")
        void exceptionWinsOnlyOnItsDate() {
            BusinessHours hours = regular().withException(MONDAY_21, open("10:00", "14:00"), TODAY);

            assertThat(hours.on(MONDAY_21)).isEqualTo(open("10:00", "14:00"));
            assertThat(hours.on(MONDAY_21.plusWeeks(1))).isEqualTo(open("08:00", "18:00"));
        }

        @Test
        @DisplayName("a excecao pode ser fechado")
        void exceptionCanBeClosed() {
            BusinessHours hours = regular().withException(MONDAY_21, DayHours.closedDay(), TODAY);

            assertThat(hours.on(MONDAY_21).closed()).isTrue();
        }

        @Test
        @DisplayName("hoje e aceito; data passada e recusada")
        void pastDateRejected() {
            assertThat(regular().withException(TODAY, DayHours.closedDay(), TODAY).on(TODAY).closed()).isTrue();
            assertThatThrownBy(() -> regular().withException(TODAY.minusDays(1), DayHours.closedDay(), TODAY))
                    .isInstanceOf(InvalidBusinessHoursException.class);
        }

        @Test
        @DisplayName("remover a excecao devolve a data ao dia da semana; passado nao se remove")
        void removeException() {
            BusinessHours hours = regular().withException(MONDAY_21, DayHours.closedDay(), TODAY);

            assertThat(hours.withoutException(MONDAY_21, TODAY).on(MONDAY_21)).isEqualTo(open("08:00", "18:00"));
            assertThatThrownBy(() -> hours.withoutException(TODAY.minusDays(1), TODAY))
                    .isInstanceOf(InvalidBusinessHoursException.class);
        }

        @Test
        @DisplayName("trocar a semana mantem a excecao da data (decisao de 16/09)")
        void weekChangeKeepsException() {
            BusinessHours hours = regular().withException(MONDAY_21, open("10:00", "14:00"), TODAY);

            List<WeekdayHours> week = regularWeek();
            week.set(0, new WeekdayHours(DayOfWeek.MONDAY, open("07:00", "15:00")));
            BusinessHours changed = hours.withWeek(week);

            assertThat(changed.on(MONDAY_21)).isEqualTo(open("10:00", "14:00"));
            assertThat(changed.on(MONDAY_21.plusWeeks(1))).isEqualTo(open("07:00", "15:00"));
        }

        @Test
        @DisplayName("loja nao configurada nao aceita excecao — primeiro a semana")
        void exceptionNeedsWeek() {
            assertThatThrownBy(() -> BusinessHours.notConfigured().withException(MONDAY_21, DayHours.closedDay(), TODAY))
                    .isInstanceOf(InvalidBusinessHoursException.class);
        }

        @Test
        @DisplayName("as excecoes de hoje em diante saem em ordem de data")
        void upcomingExceptions() {
            BusinessHours hours = BusinessHours.of(
                    regularWeek(),
                    Map.of(TODAY.minusDays(3), DayHours.closedDay(),
                            MONDAY_21.plusWeeks(1), DayHours.closedDay(),
                            MONDAY_21, open("10:00", "14:00")));

            assertThat(hours.exceptionsFrom(TODAY).keySet()).containsExactly(MONDAY_21, MONDAY_21.plusWeeks(1));
        }
    }

    @Nested
    @DisplayName("dentro do horario (a pergunta do auto-fechamento e da intencao)")
    class Within {

        @Test
        @DisplayName("janela normal [abre, fecha)")
        void normalWindow() {
            BusinessHours hours = regular();

            assertThat(hours.isWithin(MONDAY_21, t("08:00"))).isTrue();
            assertThat(hours.isWithin(MONDAY_21, t("17:59"))).isTrue();
            assertThat(hours.isWithin(MONDAY_21, t("18:00"))).isFalse();
            assertThat(hours.isWithin(MONDAY_21, t("07:59"))).isFalse();
        }

        @Test
        @DisplayName("dia fechado: nunca dentro")
        void closedDay() {
            assertThat(regular().isWithin(MONDAY_21.plusDays(6), t("10:00"))).isFalse();
        }

        @Test
        @DisplayName("usa o horario DA DATA — sabado fecha ao meio-dia")
        void usesTheDateHours() {
            assertThat(regular().isWithin(MONDAY_21.plusDays(5), t("13:00"))).isFalse();
            assertThat(regular().isWithin(MONDAY_21, t("13:00"))).isTrue();
        }

        @Test
        @DisplayName("virada da meia-noite: a madrugada pertence ao dia em que abriu")
        void overnightSpillsIntoNextDay() {
            List<WeekdayHours> week = new ArrayList<>(Arrays.stream(DayOfWeek.values())
                    .map(d -> new WeekdayHours(d, DayHours.closedDay())).toList());
            week.set(4, new WeekdayHours(DayOfWeek.FRIDAY, open("22:00", "02:00")));
            BusinessHours hours = BusinessHours.weekly(week);
            LocalDate friday = MONDAY_21.plusDays(4);
            LocalDate saturday = friday.plusDays(1);

            assertThat(hours.isWithin(friday, t("23:00"))).isTrue();
            assertThat(hours.isWithin(saturday, t("01:30"))).isTrue();
            assertThat(hours.isWithin(saturday, t("02:00"))).isFalse();
            assertThat(hours.isWithin(friday, t("01:30"))).isFalse(); // quinta estava fechada
        }

        @Test
        @DisplayName("24h: qualquer hora do dia")
        void allDay() {
            BusinessHours hours = BusinessHours.everyDay(open("00:00", "00:00"));

            assertThat(hours.isWithin(MONDAY_21, t("00:00"))).isTrue();
            assertThat(hours.isWithin(MONDAY_21, t("23:59"))).isTrue();
        }

        @Test
        @DisplayName("nao configurada: qualquer hora vale (comportamento de hoje sem horario)")
        void notConfiguredIsAlwaysWithin() {
            assertThat(BusinessHours.notConfigured().isWithin(MONDAY_21, t("03:00"))).isTrue();
        }

        @Test
        @DisplayName("excecao fechada numa segunda: fora, mesmo no horario de costume")
        void closedException() {
            BusinessHours hours = regular().withException(MONDAY_21, DayHours.closedDay(), TODAY);

            assertThat(hours.isWithin(MONDAY_21, t("10:00"))).isFalse();
        }
    }
}
