package com.ospaulos.vupth.unit.establishment;

import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.service.ClosingTimeMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;

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

    private Establishment estab(OffsetTime opening, OffsetTime closing) {
        Establishment e = new Establishment();
        e.setOpeningTime(opening);
        e.setClosingTime(closing);
        return e;
    }

    private OffsetTime utc(int h, int m) {
        return OffsetTime.of(h, m, 0, 0, ZoneOffset.UTC);
    }

    private OffsetDateTime at(int day, int h, int m) {
        return OffsetDateTime.of(2026, 7, day, h, m, 0, 0, ZoneOffset.UTC);
    }

    @Nested
    @DisplayName("hasClosingEvent")
    class HasClosingEvent {

        @Test
        @DisplayName("janela normal tem fechamento")
        void normalWindowHasEvent() {
            assertThat(ClosingTimeMath.hasClosingEvent(estab(utc(9, 0), utc(18, 0)))).isTrue();
        }

        @Test
        @DisplayName("janela nula (opening/closing null) nao tem fechamento")
        void nullWindowNoEvent() {
            assertThat(ClosingTimeMath.hasClosingEvent(estab(null, null))).isFalse();
            assertThat(ClosingTimeMath.hasClosingEvent(estab(utc(9, 0), null))).isFalse();
            assertThat(ClosingTimeMath.hasClosingEvent(estab(null, utc(18, 0)))).isFalse();
        }

        @Test
        @DisplayName("24h (open == close) nunca fecha")
        void degenerate24hNoEvent() {
            assertThat(ClosingTimeMath.hasClosingEvent(estab(utc(0, 0), utc(0, 0)))).isFalse();
        }
    }

    @Nested
    @DisplayName("isJustAfterClosing (gatilho do prompt)")
    class IsJustAfterClosing {

        private final Establishment e = estab(utc(9, 0), utc(18, 0));

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
            Establishment always = estab(utc(0, 0), utc(0, 0));
            assertThat(ClosingTimeMath.isJustAfterClosing(always, at(24, 3, 0), HORIZON)).isFalse();
        }
    }

    @Nested
    @DisplayName("ocorrencias do closing_time")
    class Occurrences {

        private final Establishment e = estab(utc(9, 0), utc(18, 0));

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

        private final Establishment e = estab(utc(22, 0), utc(2, 0));

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
}
