package com.ospaulos.vupth.domain.attendance.service;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceInvalidStatusException;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.queue.exception.ClientNotInQueueException;
import com.ospaulos.vupth.domain.queue.swap.service.QueueSwapService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Queda brusca (DEC-128): ok, preciso de mais tempo e passar a vez. */
class SuddenDropServiceTest {

    private final AttendanceRepository attendances = mock(AttendanceRepository.class);
    private final EarlierTurnService earlier = mock(EarlierTurnService.class);
    private final QueueSwapService swaps = mock(QueueSwapService.class);
    private final SuddenDropService service = new SuddenDropService(attendances, earlier, swaps);
    private final UUID clientId = UUID.randomUUID();

    private Attendance attendance(boolean drop) {
        Attendance a = new Attendance();
        a.setId(UUID.randomUUID());
        a.setStatus(AttendanceStatus.SCHEDULED);
        Client c = mock(Client.class);
        when(c.getId()).thenReturn(clientId);
        a.setClient(c);
        Professional p = mock(Professional.class);
        when(p.getId()).thenReturn(UUID.randomUUID());
        a.setProfessional(p);
        Establishment e = mock(Establishment.class);
        when(e.getId()).thenReturn(UUID.randomUUID());
        a.setEstablishment(e);
        if (drop) {
            a.setSuddenDropAt(OffsetDateTime.now());
        }
        when(attendances.findByIdEager(a.getId())).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    @DisplayName("ok: aceita o horario novo (vira o prometido) e fecha a janela")
    void acknowledge() {
        Attendance a = attendance(true);
        OffsetDateTime promised = OffsetDateTime.now().plusMinutes(10);
        when(earlier.acceptEarlierTurn(a.getId(), clientId)).thenReturn(promised);

        assertThat(service.acknowledge(a.getId(), clientId)).isEqualTo(promised);
        assertThat(a.getSuddenDropAt()).isNull();
        verify(attendances).save(a);
    }

    @Test
    @DisplayName("ok sem queda aberta, de outro cliente ou fora da fila: recusa")
    void acknowledgeRefused() {
        Attendance none = attendance(false);
        assertThatThrownBy(() -> service.acknowledge(none.getId(), clientId)).isInstanceOf(AttendanceInvalidStatusException.class);
        Attendance other = attendance(true);
        assertThatThrownBy(() -> service.acknowledge(other.getId(), UUID.randomUUID()))
                .isInstanceOf(EstablishmentAccessDeniedException.class);
        Attendance inProgress = attendance(true);
        inProgress.setStatus(AttendanceStatus.IN_PROGRESS);
        assertThatThrownBy(() -> service.acknowledge(inProgress.getId(), clientId)).isInstanceOf(AttendanceInvalidStatusException.class);
        verify(earlier, never()).acceptEarlierTurn(any(), any());
    }

    @Test
    @DisplayName("preciso de mais tempo: as 4 opcoes enquanto a janela esta aberta; depois dela, recusa")
    void needMoreTime() {
        Attendance a = attendance(true);
        SuddenDropService.Options options = service.needMoreTime(a.getId(), clientId);
        assertThat(options.options()).extracting(SuddenDropService.Option::action)
                .containsExactly("SWAP", "PASS_TURN", "LEAVE_QUEUE", "MOVE_TO_END");
        assertThat(options.windowUntil()).isAfter(OffsetDateTime.now());

        Attendance expired = attendance(false);
        expired.setSuddenDropAt(OffsetDateTime.now().minusMinutes(25));
        assertThatThrownBy(() -> service.needMoreTime(expired.getId(), clientId)).isInstanceOf(AttendanceInvalidStatusException.class);
    }

    @Test
    @DisplayName("passar a vez: propoe a troca com o proximo da fila; sem proximo, recusa")
    void passTurn() {
        Attendance a = attendance(true);
        Attendance next = attendance(false);
        when(attendances.findWaitingQueueByProfessionalAndEstablishment(any(), any())).thenReturn(List.of(a, next));
        service.passTurn(a.getId(), clientId);
        verify(swaps).propose(a.getId(), next.getId(), clientId);

        Attendance last = attendance(true);
        when(attendances.findWaitingQueueByProfessionalAndEstablishment(any(), any())).thenReturn(List.of(last));
        assertThatThrownBy(() -> service.passTurn(last.getId(), clientId)).isInstanceOf(ClientNotInQueueException.class);
    }
}
