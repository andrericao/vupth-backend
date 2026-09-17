package com.ospaulos.vupth.unit.establishment;

import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.service.ClosingTimeMath;
import com.ospaulos.vupth.domain.establishment.valueObject.BusinessHours;
import com.ospaulos.vupth.domain.establishment.valueObject.DayHours;
import com.ospaulos.vupth.domain.establishment.valueObject.WeekdayHours;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ospaulos.vupth.domain.shared.BusinessClock;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests — {@link ClosingTimeMath}, a matematica pura da janela de expediente do auto-fechamento
 * (S1 fatia 5 / BUG1). Converte o {@code closing_time} RECORRENTE em instantes concretos.
 *
 * Cobre: loja sem fechamento (24h / janela nula), a regiao "logo apos o fechamento" (gatilho do
 * prompt) com o horizonte que poupa quem chega cedo, ocorrencia mais recente / proxima, e a virada
 * de meia-noite.
 */
class ClosingTimeMathTest {

    private static final Duration HORIZON = Duration.ofHours(6);

    private Establishment estab(LocalTime opening, LocalTime closing) {
        Establishment e = new Establishment();
        e.applySingleWindow(opening, closing);
        return e;
    }

    /** Hora de PAREDE no fuso da loja (BUG2b — opening/closing deixaram de carregar offset). */
    private LocalTime wall(int h, int m) {
        return LocalTime.of(h, m);
    }

    /** Instante em que o relogio da loja marca {@code h:m} no dia informado. */
    private OffsetDateTime at(int day, int h, int m) {
        return BusinessClock.at(LocalDate.of(2026, 7, day), LocalTime.of(h, m));
    }

    @Nested
    @DisplayName("hasClosingEvent")
    class HasClosingEvent {

        @Test
        @DisplayName("janela normal tem fechamento")
        void normalWindowHasEvent() {
            assertThat(ClosingTimeMath.hasClosingEvent(estab(wall(9, 0), wall(18, 0)))).isTrue();
        }

        @Test
        @DisplayName("janela nula (opening/closing null) nao tem fechamento")
        void nullWindowNoEvent() {
            assertThat(ClosingTimeMath.hasClosingEvent(estab(null, null))).isFalse();
            assertThat(ClosingTimeMath.hasClosingEvent(estab(wall(9, 0), null))).isFalse();
            assertThat(ClosingTimeMath.hasClosingEvent(estab(null, wall(18, 0)))).isFalse();
        }

        @Test
        @DisplayName("24h (open == close) nunca fecha")
        void degenerate24hNoEvent() {
            assertThat(ClosingTimeMath.hasClosingEvent(estab(wall(0, 0), wall(0, 0)))).isFalse();
        }
    }

    @Nested
    @DisplayName("isJustAfterClosing (gatilho do prompt)")
    class IsJustAfterClosing {

        private final Establishment e = estab(wall(9, 0), wall(18, 0));

        @Test
        @DisplayName("dentro da janela = nao (nada a fechar)")
        void insideWindowFalse() {
            assertThat(ClosingTimeMath.isJustAfterClosing(e, at(24, 12, 0), HORIZON)).isFalse();
        }

        @Test
        @DisplayName("logo apos o fechamento (dentro do horizonte) = sim")
        void justAfterCloseTrue() {
            assertThat(ClosingTimeMath.isJustAfterClosing(e, at(24, 18, 30), HORIZON)).isTrue();
            assertThat(ClosingTimeMath.isJustAfterClosing(e, at(24, 23, 0), HORIZON)).isTrue(); // 5h apos
        }

        @Test
        @DisplayName("madrugada / antes de abrir (fora do horizonte) = nao (nao incomoda quem chega cedo)")
        void earlyMorningFalse() {
            // 02:00 do dia seguinte: 8h apos o fechamento das 18:00 do dia anterior > horizonte de 6h
            assertThat(ClosingTimeMath.isJustAfterClosing(e, at(25, 2, 0), HORIZON)).isFalse();
        }

        @Test
        @DisplayName("loja 24h nunca dispara o prompt")
        void store24hNeverPrompts() {
            Establishment always = estab(wall(0, 0), wall(0, 0));
            assertThat(ClosingTimeMath.isJustAfterClosing(always, at(24, 3, 0), HORIZON)).isFalse();
        }
    }

    @Nested
    @DisplayName("ocorrencias do closing_time")
    class Occurrences {

        private final Establishment e = estab(wall(9, 0), wall(18, 0));

        @Test
        @DisplayName("mostRecent: apos o fechamento de hoje = hoje 18:00")
        void mostRecentToday() {
            assertThat(ClosingTimeMath.mostRecentClosingInstant(e, at(24, 20, 0))).isEqualTo(at(24, 18, 0));
        }

        @Test
        @DisplayName("mostRecent: antes do fechamento de hoje = ontem 18:00")
        void mostRecentYesterday() {
            assertThat(ClosingTimeMath.mostRecentClosingInstant(e, at(24, 5, 0))).isEqualTo(at(23, 18, 0));
        }

        @Test
        @DisplayName("next: sempre a proxima ocorrencia futura")
        void nextClosing() {
            assertThat(ClosingTimeMath.nextClosingInstant(e, at(24, 20, 0))).isEqualTo(at(25, 18, 0));
            assertThat(ClosingTimeMath.nextClosingInstant(e, at(24, 5, 0))).isEqualTo(at(24, 18, 0));
        }
    }

    @Nested
    @DisplayName("virada de meia-noite [22:00, 02:00)")
    class Overnight {

        private final Establishment e = estab(wall(22, 0), wall(2, 0));

        @Test
        @DisplayName("logo apos o fechamento das 02:00 = sim")
        void justAfterOvernightClose() {
            assertThat(ClosingTimeMath.isJustAfterClosing(e, at(24, 3, 0), HORIZON)).isTrue();
        }

        @Test
        @DisplayName("meio do dia (fora da janela, mas longe do fechamento) = nao")
        void middayFalse() {
            assertThat(ClosingTimeMath.isJustAfterClosing(e, at(24, 12, 0), HORIZON)).isFalse();
        }

        @Test
        @DisplayName("madrugada dentro da janela = nao")
        void insideOvernightFalse() {
            assertThat(ClosingTimeMath.isJustAfterClosing(e, at(24, 1, 0), HORIZON)).isFalse();
        }
    }

    /**
     * Horario por dia (#2): seg-sex 08-18, sab 08-12, dom fechado. Julho/2026: 23 = quinta,
     * 24 = sexta, 25 = sabado, 26 = domingo, 27 = segunda.
     */
    @Nested
    @DisplayName("horario por dia")
    class PerDay {

        private Establishment weekly(Map<LocalDate, DayHours> exceptions) {
            List<WeekdayHours> week = new ArrayList<>();
            for (DayOfWeek day : DayOfWeek.values()) {
                week.add(new WeekdayHours(day, switch (day) {
                    case SATURDAY -> DayHours.open(wall(8, 0), wall(12, 0));
                    case SUNDAY -> DayHours.closedDay();
                    default -> DayHours.open(wall(8, 0), wall(18, 0));
                }));
            }
            Establishment e = new Establishment();
            e.applyBusinessHours(BusinessHours.of(week, exceptions));
            return e;
        }

        @Test
        @DisplayName("sabado fecha ao meio-dia: 12:30 e logo apos o fechamento; 19:00 ja nao e")
        void saturdayClosesAtNoon() {
            Establishment e = weekly(Map.of());
            assertThat(ClosingTimeMath.isJustAfterClosing(e, at(25, 12, 30), HORIZON)).isTrue();
            assertThat(ClosingTimeMath.isJustAfterClosing(e, at(25, 19, 0), HORIZON)).isFalse();
            assertThat(ClosingTimeMath.mostRecentClosingInstant(e, at(25, 12, 30))).isEqualTo(at(25, 12, 0));
        }

        @Test
        @DisplayName("domingo fechado: nao ha fechamento no domingo, e o proximo e segunda as 18:00")
        void sundayClosed() {
            Establishment e = weekly(Map.of());
            assertThat(ClosingTimeMath.isJustAfterClosing(e, at(26, 10, 0), HORIZON)).isFalse();
            assertThat(ClosingTimeMath.nextClosingInstant(e, at(25, 13, 0))).isEqualTo(at(27, 18, 0));
        }

        @Test
        @DisplayName("excecao fechada na sexta: o proximo fechamento depois de quinta e o de sabado")
        void closedException() {
            Establishment e = weekly(Map.of(LocalDate.of(2026, 7, 24), DayHours.closedDay()));
            assertThat(ClosingTimeMath.nextClosingInstant(e, at(23, 20, 0))).isEqualTo(at(25, 12, 0));
        }

        @Test
        @DisplayName("excecao com horario proprio: fecha no horario da excecao")
        void exceptionWithOwnHours() {
            Establishment e = weekly(Map.of(LocalDate.of(2026, 7, 24), DayHours.open(wall(10, 0), wall(14, 0))));
            assertThat(ClosingTimeMath.isJustAfterClosing(e, at(24, 14, 30), HORIZON)).isTrue();
            assertThat(ClosingTimeMath.nextClosingInstant(e, at(24, 9, 0))).isEqualTo(at(24, 14, 0));
        }
    }
}
