package com.ospaulos.vupth.domain.queue.service.impl;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.client.model.Client;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Queda brusca da estimativa (DEC-128): 30 min ou mais de uma vez, ate 3 vezes, janela de 20 min. */
class SuddenDropDetectionTest {

    private final OffsetDateTime now = OffsetDateTime.now();

    private Attendance withClient() {
        Attendance a = new Attendance();
        a.setId(java.util.UUID.randomUUID());
        a.setClient(mock(Client.class));
        return a;
    }

    @Test
    @DisplayName("queda de 30 min ou mais: marca o momento, a estimativa antiga e liga o aviso")
    void registersTheDrop() {
        Attendance a = withClient();
        QueueServiceImpl.registerSuddenDrop(a, now.plusMinutes(90), now.plusMinutes(60));
        assertThat(a.getSuddenDropAt()).isNotNull();
        assertThat(a.getSuddenDropFrom()).isEqualTo(now.plusMinutes(90));
        assertThat(a.getSuddenDropCount()).isEqualTo(1);
        assertThat(a.isSuddenDropNotified()).isFalse();
        assertThat(a.isSuddenDropWindowOpen(OffsetDateTime.now())).isTrue();
        assertThat(a.isSuddenDropWindowOpen(OffsetDateTime.now().plusMinutes(21))).isFalse();
    }

    @Test
    @DisplayName("menos de 30 min, sem estimativa anterior ou convidado: nada")
    void ignores() {
        Attendance small = withClient();
        QueueServiceImpl.registerSuddenDrop(small, now.plusMinutes(90), now.plusMinutes(61));
        assertThat(small.getSuddenDropAt()).isNull();
        Attendance fresh = withClient();
        QueueServiceImpl.registerSuddenDrop(fresh, null, now);
        assertThat(fresh.getSuddenDropAt()).isNull();
        Attendance guest = new Attendance();
        QueueServiceImpl.registerSuddenDrop(guest, now.plusMinutes(90), now);
        assertThat(guest.getSuddenDropAt()).isNull();
        assertThat(small.isSuddenDropWindowOpen(OffsetDateTime.now())).isFalse();
    }

    @Test
    @DisplayName("nova queda estende a janela e religa o aviso; passa de 3, para")
    void limit() {
        Attendance a = withClient();
        for (int i = 0; i < 4; i++) {
            a.setSuddenDropNotified(true);
            QueueServiceImpl.registerSuddenDrop(a, now.plusMinutes(120), now.plusMinutes(60));
        }
        assertThat(a.getSuddenDropCount()).isEqualTo(Attendance.SUDDEN_DROP_MAX);
        assertThat(a.isSuddenDropNotified()).isTrue(); // a 4a queda nao religou o aviso
    }
}
