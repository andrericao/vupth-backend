package com.ospaulos.vupth.unit.establishment;

import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests — Establishment.isOpenNow ("aberto agora" efetivo).
 *
 * <p><b>Regra nova (Andre, 17–18/09 — D11 do Client):</b> quem abre a loja é a <b>chegada do primeiro
 * profissional</b>, não o relógio. O {@code open_status} já deriva de quem está em expediente, então
 * "aberto agora" é <b>só</b> o {@code open_status}: o horário publicado é o <b>plano do dia</b>, não
 * autorização. Antes, "aberto" exigia também estar dentro da janela — e no aparelho isso produziu o
 * caso que o Andre decidiu (17/09): profissional <i>Trabalhando</i>, fila aberta, e a loja aparecendo
 * FECHADA porque o relógio tinha passado do horário.</p>
 *
 * <p>O horário continua valendo onde ele é previsão: o auto-fechamento no fim do expediente (que leva
 * o profissional a {@code OFF} e, por derivação, fecha a loja) e a agenda dos próximos dias.</p>
 */
class EstablishmentIsOpenNowTest {

    private Establishment estab(OpenStatus status, LocalTime opening, LocalTime closing) {
        Establishment e = new Establishment();
        e.setOpenStatus(status);
        e.applySingleWindow(opening, closing);
        return e;
    }

    private LocalTime wall(int h, int m) {
        return LocalTime.of(h, m);
    }

    @Test
    @DisplayName("não-OPEN é fechado, mesmo dentro do horário publicado")
    void notOpenIsClosed() {
        assertThat(estab(OpenStatus.CLOSED, wall(9, 0), wall(18, 0)).isOpenNow()).isFalse();
        assertThat(estab(OpenStatus.TEMPORARILY_CLOSED, wall(9, 0), wall(18, 0)).isOpenNow()).isFalse();
    }

    @Test
    @DisplayName("OPEN é aberto FORA do horário publicado — quem abre é o profissional, não o relógio")
    void openOutsideTheWindowIsOpen() {
        // A loja só está OPEN porque alguém marcou "trabalhando" (o open_status deriva disso).
        Establishment e = estab(OpenStatus.OPEN, wall(8, 0), wall(18, 0));

        assertThat(e.isOpenNow()).isTrue();
    }

    @Test
    @DisplayName("OPEN sem horário definido continua aberto")
    void openWithoutHoursIsOpen() {
        assertThat(estab(OpenStatus.OPEN, null, null).isOpenNow()).isTrue();
    }

    @Test
    @DisplayName("o horário publicado continua legível — ele é o plano do dia, não o portão")
    void publishedHoursAreStillReadable() {
        Establishment e = estab(OpenStatus.OPEN, wall(8, 0), wall(18, 0));

        assertThat(e.todayOpens()).isEqualTo(wall(8, 0));
        assertThat(e.todayCloses()).isEqualTo(wall(18, 0));
        assertThat(e.isWithinBusinessHours(wall(20, 0))).isFalse();
        assertThat(e.isWithinBusinessHours(wall(9, 0))).isTrue();
    }
}
