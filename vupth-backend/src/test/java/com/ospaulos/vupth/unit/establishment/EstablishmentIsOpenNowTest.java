package com.ospaulos.vupth.unit.establishment;

import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests — Establishment.isOpenAt (nucleo puro do "aberto agora" efetivo, BUG5 parte B).
 *
 * Regra: aberto == open_status OPEN E horario dentro de [opening, closing).
 * Sem janela (opening/closing nulos) so o open_status manda.
 * Cobre janela normal, borda [inclusivo,exclusivo), 24h, virada de meia-noite e offsets.
 */
class EstablishmentIsOpenNowTest {

    private Establishment estab(OpenStatus status, OffsetTime opening, OffsetTime closing) {
        Establishment e = new Establishment();
        e.setOpenStatus(status);
        e.setOpeningTime(opening);
        e.setClosingTime(closing);
        return e;
    }

    private OffsetTime utc(int h, int m) {
        return OffsetTime.of(h, m, 0, 0, ZoneOffset.UTC);
    }

    @Nested
    @DisplayName("open_status manda")
    class OpenStatusGate {

        @Test
        @DisplayName("nao-OPEN sempre fechado, mesmo dentro da janela")
        void notOpenAlwaysClosed() {
            Establishment e = estab(OpenStatus.CLOSED, utc(9, 0), utc(18, 0));
            assertThat(e.isOpenAt(utc(12, 0))).isFalse();

            e.setOpenStatus(OpenStatus.TEMPORARILY_CLOSED);
            assertThat(e.isOpenAt(utc(12, 0))).isFalse();
        }

        @Test
        @DisplayName("OPEN sem janela definida = aberto (so o status manda)")
        void openWithoutWindowIsOpen() {
            assertThat(estab(OpenStatus.OPEN, null, null).isOpenAt(utc(3, 0))).isTrue();
            assertThat(estab(OpenStatus.OPEN, utc(9, 0), null).isOpenAt(utc(3, 0))).isTrue();
            assertThat(estab(OpenStatus.OPEN, null, utc(18, 0)).isOpenAt(utc(3, 0))).isTrue();
        }
    }

    @Nested
    @DisplayName("janela normal [open, close)")
    class NormalWindow {

        private final Establishment e = estab(OpenStatus.OPEN, utc(9, 0), utc(18, 0));

        @Test
        @DisplayName("dentro da janela = aberto")
        void insideOpen() {
            assertThat(e.isOpenAt(utc(12, 0))).isTrue();
        }

        @Test
        @DisplayName("abertura inclusiva, fechamento exclusivo")
        void bordersInclusiveOpenExclusiveClose() {
            assertThat(e.isOpenAt(utc(9, 0))).isTrue();   // abre: inclusivo
            assertThat(e.isOpenAt(utc(18, 0))).isFalse(); // fecha: exclusivo
        }

        @Test
        @DisplayName("antes de abrir e depois de fechar = fechado")
        void outsideClosed() {
            assertThat(e.isOpenAt(utc(8, 59))).isFalse();
            assertThat(e.isOpenAt(utc(18, 1))).isFalse();
        }
    }

    @Nested
    @DisplayName("casos degenerados / virada")
    class EdgeWindows {

        @Test
        @DisplayName("open == close = 24h (sempre aberto se OPEN)")
        void degenerate24h() {
            Establishment e = estab(OpenStatus.OPEN, utc(0, 0), utc(0, 0));
            assertThat(e.isOpenAt(utc(3, 0))).isTrue();
            assertThat(e.isOpenAt(utc(23, 59))).isTrue();
        }

        @Test
        @DisplayName("janela atravessa a meia-noite [22:00, 02:00)")
        void overnight() {
            Establishment e = estab(OpenStatus.OPEN, utc(22, 0), utc(2, 0));
            assertThat(e.isOpenAt(utc(23, 0))).isTrue();  // depois de abrir
            assertThat(e.isOpenAt(utc(1, 0))).isTrue();   // antes de fechar (madrugada)
            assertThat(e.isOpenAt(utc(22, 0))).isTrue();  // abre: inclusivo
            assertThat(e.isOpenAt(utc(2, 0))).isFalse();  // fecha: exclusivo
            assertThat(e.isOpenAt(utc(12, 0))).isFalse(); // meio do dia = fechado
        }
    }

    @Nested
    @DisplayName("offsets sao normalizados para o mesmo instante")
    class Offsets {

        @Test
        @DisplayName("janela em -03:00 comparada a um agora em UTC")
        void differentOffsets() {
            // 09:00-03:00 == 12:00Z ; 18:00-03:00 == 21:00Z
            Establishment e = estab(OpenStatus.OPEN,
                    OffsetTime.of(9, 0, 0, 0, ZoneOffset.ofHours(-3)),
                    OffsetTime.of(18, 0, 0, 0, ZoneOffset.ofHours(-3)));
            assertThat(e.isOpenAt(utc(11, 0))).isFalse(); // 11:00Z = 08:00-03 (antes de abrir)
            assertThat(e.isOpenAt(utc(15, 0))).isTrue();  // 15:00Z = 12:00-03 (dentro)
            assertThat(e.isOpenAt(utc(21, 0))).isFalse(); // 21:00Z = 18:00-03 (fecha, exclusivo)
        }
    }
}
