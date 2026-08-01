package com.ospaulos.vupth.unit.establishment;

import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ospaulos.vupth.domain.shared.BusinessClock;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests — Establishment.isOpenAt (nucleo puro do "aberto agora" efetivo, BUG5 parte B).
 *
 * Regra: aberto == open_status OPEN E horario dentro de [opening, closing).
 * Sem janela (opening/closing nulos) so o open_status manda.
 * Cobre janela normal, borda [inclusivo,exclusivo), 24h, virada de meia-noite e a conversao de
 * instante -> hora de parede do fuso da loja (BUG2b).
 */
class EstablishmentIsOpenNowTest {

    private Establishment estab(OpenStatus status, LocalTime opening, LocalTime closing) {
        Establishment e = new Establishment();
        e.setOpenStatus(status);
        e.setOpeningTime(opening);
        e.setClosingTime(closing);
        return e;
    }

    /** Hora de PAREDE no fuso da loja (BUG2b — a janela deixou de carregar offset). */
    private LocalTime wall(int h, int m) {
        return LocalTime.of(h, m);
    }

    @Nested
    @DisplayName("open_status manda")
    class OpenStatusGate {

        @Test
        @DisplayName("nao-OPEN sempre fechado, mesmo dentro da janela")
        void notOpenAlwaysClosed() {
            Establishment e = estab(OpenStatus.CLOSED, wall(9, 0), wall(18, 0));
            assertThat(e.isOpenAt(wall(12, 0))).isFalse();

            e.setOpenStatus(OpenStatus.TEMPORARILY_CLOSED);
            assertThat(e.isOpenAt(wall(12, 0))).isFalse();
        }

        @Test
        @DisplayName("OPEN sem janela definida = aberto (so o status manda)")
        void openWithoutWindowIsOpen() {
            assertThat(estab(OpenStatus.OPEN, null, null).isOpenAt(wall(3, 0))).isTrue();
            assertThat(estab(OpenStatus.OPEN, wall(9, 0), null).isOpenAt(wall(3, 0))).isTrue();
            assertThat(estab(OpenStatus.OPEN, null, wall(18, 0)).isOpenAt(wall(3, 0))).isTrue();
        }
    }

    @Nested
    @DisplayName("janela normal [open, close)")
    class NormalWindow {

        private final Establishment e = estab(OpenStatus.OPEN, wall(9, 0), wall(18, 0));

        @Test
        @DisplayName("dentro da janela = aberto")
        void insideOpen() {
            assertThat(e.isOpenAt(wall(12, 0))).isTrue();
        }

        @Test
        @DisplayName("abertura inclusiva, fechamento exclusivo")
        void bordersInclusiveOpenExclusiveClose() {
            assertThat(e.isOpenAt(wall(9, 0))).isTrue();   // abre: inclusivo
            assertThat(e.isOpenAt(wall(18, 0))).isFalse(); // fecha: exclusivo
        }

        @Test
        @DisplayName("antes de abrir e depois de fechar = fechado")
        void outsideClosed() {
            assertThat(e.isOpenAt(wall(8, 59))).isFalse();
            assertThat(e.isOpenAt(wall(18, 1))).isFalse();
        }
    }

    @Nested
    @DisplayName("casos degenerados / virada")
    class EdgeWindows {

        @Test
        @DisplayName("open == close = 24h (sempre aberto se OPEN)")
        void degenerate24h() {
            Establishment e = estab(OpenStatus.OPEN, wall(0, 0), wall(0, 0));
            assertThat(e.isOpenAt(wall(3, 0))).isTrue();
            assertThat(e.isOpenAt(wall(23, 59))).isTrue();
        }

        @Test
        @DisplayName("janela atravessa a meia-noite [22:00, 02:00)")
        void overnight() {
            Establishment e = estab(OpenStatus.OPEN, wall(22, 0), wall(2, 0));
            assertThat(e.isOpenAt(wall(23, 0))).isTrue();  // depois de abrir
            assertThat(e.isOpenAt(wall(1, 0))).isTrue();   // antes de fechar (madrugada)
            assertThat(e.isOpenAt(wall(22, 0))).isTrue();  // abre: inclusivo
            assertThat(e.isOpenAt(wall(2, 0))).isFalse();  // fecha: exclusivo
            assertThat(e.isOpenAt(wall(12, 0))).isFalse(); // meio do dia = fechado
        }
    }

    @Nested
    @DisplayName("instante -> hora de parede da loja (BUG2b)")
    class WallClockConversion {

        private final Establishment e = estab(OpenStatus.OPEN, wall(9, 0), wall(18, 0));

        @Test
        @DisplayName("o gate compara a hora LOCAL da loja, nao a hora UTC do instante")
        void instantIsConvertedToBusinessZone() {
            // Fuso de negocio = America/Sao_Paulo (UTC-3). 12:00Z = 09:00 local (abre: inclusivo);
            // 11:00Z = 08:00 local (ainda fechado). Passar a hora UTC crua daria o resultado oposto.
            OffsetDateTime noonUtc = OffsetDateTime.of(2026, 7, 24, 12, 0, 0, 0, ZoneOffset.UTC);
            OffsetDateTime elevenUtc = OffsetDateTime.of(2026, 7, 24, 11, 0, 0, 0, ZoneOffset.UTC);

            assertThat(BusinessClock.timeAt(noonUtc)).isEqualTo(wall(9, 0));
            assertThat(e.isOpenAt(BusinessClock.timeAt(noonUtc))).isTrue();
            assertThat(e.isOpenAt(BusinessClock.timeAt(elevenUtc))).isFalse();
        }

        @Test
        @DisplayName("o MESMO instante escrito em outro offset da a mesma hora de parede")
        void sameInstantAnyOffsetSameWallClock() {
            OffsetDateTime asUtc = OffsetDateTime.of(2026, 7, 24, 15, 0, 0, 0, ZoneOffset.UTC);
            OffsetDateTime asLocal = OffsetDateTime.of(2026, 7, 24, 12, 0, 0, 0, ZoneOffset.ofHours(-3));

            assertThat(BusinessClock.timeAt(asUtc)).isEqualTo(BusinessClock.timeAt(asLocal));
            assertThat(e.isOpenAt(BusinessClock.timeAt(asLocal))).isTrue(); // 12:00 local = dentro
        }
    }
}
