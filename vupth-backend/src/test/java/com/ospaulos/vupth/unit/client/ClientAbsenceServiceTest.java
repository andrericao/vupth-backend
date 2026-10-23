package com.ospaulos.vupth.unit.client;

import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.client.event.ClientAbsenceEvent;
import com.ospaulos.vupth.domain.client.model.ClientAbsence;
import com.ospaulos.vupth.domain.client.model.ClientAbsenceType;
import com.ospaulos.vupth.domain.client.repository.ClientAbsenceRepository;
import com.ospaulos.vupth.domain.client.service.ClientAbsenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tres faltas, pagar adiantado (DEC-105/115): o contador, a janela de 90 dias e o zerar ao concluir um atendimento pago. */
class ClientAbsenceServiceTest {

    private final ClientAbsenceRepository repository = mock(ClientAbsenceRepository.class);
    private final AttendanceRepository attendances = mock(AttendanceRepository.class);
    private final ClientAbsenceService service = new ClientAbsenceService(repository, attendances);
    private final UUID clientId = UUID.randomUUID();

    @Test
    @DisplayName("grava a ocorrencia uma vez por atendimento e tipo")
    void records() {
        UUID attendance = UUID.randomUUID();
        service.onAbsence(new ClientAbsenceEvent(clientId, attendance, ClientAbsenceType.NO_SHOW));
        verify(repository).save(any(ClientAbsence.class));

        when(repository.existsByAttendanceIdAndType(attendance, ClientAbsenceType.LEFT_QUEUE)).thenReturn(true);
        service.onAbsence(new ClientAbsenceEvent(clientId, attendance, ClientAbsenceType.LEFT_QUEUE));
        service.onAbsence(new ClientAbsenceEvent(null, attendance, ClientAbsenceType.NO_SHOW));
        verify(repository, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    @DisplayName("a terceira ocorrencia liga o pagamento adiantado; duas nao")
    void limit() {
        when(repository.countByClientIdAndCreatedAtAfter(eq(clientId), any())).thenReturn(2L);
        assertThat(service.statusOf(clientId).prepayRequired()).isFalse();
        assertThat(service.statusOf(clientId).absenceLimit()).isEqualTo(3);
        when(repository.countByClientIdAndCreatedAtAfter(eq(clientId), any())).thenReturn(3L);
        assertThat(service.prepayRequired(clientId)).isTrue();
        assertThat(service.statusOf(clientId).absenceCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("a janela e de 90 dias; um atendimento concluido e pago zera (conta so o que veio depois)")
    void window() {
        var since = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        when(repository.countByClientIdAndCreatedAtAfter(eq(clientId), since.capture())).thenReturn(0L);

        service.statusOf(clientId);
        assertThat(since.getValue()).isBefore(OffsetDateTime.now().minusDays(89)).isAfter(OffsetDateTime.now().minusDays(91));

        OffsetDateTime paid = OffsetDateTime.now().minusDays(2);
        when(attendances.lastPaidFinishedEnd(clientId)).thenReturn(paid);
        service.statusOf(clientId);
        assertThat(since.getValue()).as("pago ha 2 dias: so conta depois dele").isEqualTo(paid);

        when(attendances.lastPaidFinishedEnd(clientId)).thenReturn(OffsetDateTime.now().minusDays(200));
        service.statusOf(clientId);
        assertThat(since.getValue()).as("pago ha muito tempo: vale a janela").isAfter(OffsetDateTime.now().minusDays(91));
        verify(repository, never()).save(any());
    }
}
