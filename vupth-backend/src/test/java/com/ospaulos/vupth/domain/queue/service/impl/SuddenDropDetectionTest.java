package com.ospaulos.vupth.domain.queue.service.impl;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.client.model.Client;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Queda brusca da estimativa (DEC-128, limiares finais): 30 min se a espera anterior era de 1 h ou mais, 20 se de menos. */
class SuddenDropDetectionTest {

    private final OffsetDateTime now = OffsetDateTime.now();

    private Attendance withClient() {
        Attendance a = new Attendance();
        a.setId(java.util.UUID.randomUUID());
        a.setClient(mock(Client.class));
        return a;
    }

    @Test
    @DisplayName("espera anterior de 1h30 que cai 80 min para 10: marca o momento, a estimativa antiga e liga o aviso")
    void registersTheDrop() {
        Attendance a = withClient();
        QueueServiceImpl.registerSuddenDrop(a, now.plusMinutes(90), now.plusMinutes(10), now);
        assertThat(a.getSuddenDropAt()).isEqualTo(now);
        assertThat(a.getSuddenDropFrom()).isEqualTo(now.plusMinutes(90));
        assertThat(a.getSuddenDropCount()).isEqualTo(1);
        assertThat(a.isSuddenDropNotified()).isFalse();
        assertThat(a.isSuddenDropWindowOpen(now.plusMinutes(5))).isTrue();
        assertThat(a.isSuddenDropWindowOpen(now.plusMinutes(21))).isFalse();
    }

    @Test
    @DisplayName("espera anterior de 1 h ou mais pede queda de 30 min; de menos de 1 h, 20 min")
    void thresholdByPreviousWait() {
        Attendance longWaitSmallDrop = withClient();
        QueueServiceImpl.registerSuddenDrop(longWaitSmallDrop, now.plusMinutes(80), now.plusMinutes(55), now); // anterior 80, queda 25 < 30
        assertThat(longWaitSmallDrop.getSuddenDropAt()).isNull();
        Attendance longWait = withClient();
        QueueServiceImpl.registerSuddenDrop(longWait, now.plusMinutes(90), now.plusMinutes(55), now); // queda 35 >= 30
        assertThat(longWait.getSuddenDropAt()).isNotNull();

        Attendance shortWait = withClient();
        QueueServiceImpl.registerSuddenDrop(shortWait, now.plusMinutes(50), now.plusMinutes(25), now); // anterior 50, queda 25 >= 20
        assertThat(shortWait.getSuddenDropAt()).isNotNull();
        Attendance shortWaitSmallDrop = withClient();
        QueueServiceImpl.registerSuddenDrop(shortWaitSmallDrop, now.plusMinutes(50), now.plusMinutes(35), now); // queda 15 < 20
        assertThat(shortWaitSmallDrop.getSuddenDropAt()).isNull();
    }

    @Test
    @DisplayName("espera nova de 1 h ou mais: nao ha multa, entao nao ha janela nem aviso")
    void newWaitAboveTheZone() {
        Attendance a = withClient();
        QueueServiceImpl.registerSuddenDrop(a, now.plusMinutes(180), now.plusMinutes(90), now);
        assertThat(a.getSuddenDropAt()).isNull();
    }

    @Test
    @DisplayName("sem estimativa anterior ou convidado: nada")
    void ignores() {
        Attendance fresh = withClient();
        QueueServiceImpl.registerSuddenDrop(fresh, null, now, now);
        assertThat(fresh.getSuddenDropAt()).isNull();
        Attendance guest = new Attendance();
        QueueServiceImpl.registerSuddenDrop(guest, now.plusMinutes(90), now, now);
        assertThat(guest.getSuddenDropAt()).isNull();
    }

    @Test
    @DisplayName("um aviso por janela de 20 min (sem cadeia); depois dela, outra queda vale, ate 3")
    void oneNoticePerWindowAndLimit() {
        Attendance a = withClient();
        QueueServiceImpl.registerSuddenDrop(a, now.plusMinutes(90), now.plusMinutes(10), now);
        a.setSuddenDropNotified(true);
        QueueServiceImpl.registerSuddenDrop(a, now.plusMinutes(90), now.plusMinutes(10), now.plusMinutes(5)); // dentro da janela
        assertThat(a.getSuddenDropCount()).isEqualTo(1);
        assertThat(a.isSuddenDropNotified()).isTrue();

        OffsetDateTime later = now.plusMinutes(30);
        for (int i = 0; i < 4; i++) {
            later = later.plusMinutes(30);
            QueueServiceImpl.registerSuddenDrop(a, later.plusMinutes(90), later.plusMinutes(10), later);
        }
        assertThat(a.getSuddenDropCount()).isEqualTo(Attendance.SUDDEN_DROP_MAX);
    }
}
