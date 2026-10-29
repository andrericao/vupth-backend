package com.ospaulos.vupth.domain.notification.service;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Push "proximo da fila" e o interruptor "expor a posicao na fila" (expor-posicao, decisao de 05/09). */
@ExtendWith(MockitoExtension.class)
class QueuePushServiceTest {

    @Mock AttendanceRepository attendanceRepository;
    @Mock PushNotificationPublisher pushPublisher;
    @InjectMocks QueuePushService service;

    private final UUID establishmentId = UUID.randomUUID();
    private final UUID clientId = UUID.randomUUID();

    private Attendance headOf(Boolean showPosition) {
        Professional professional = mock(Professional.class);
        when(professional.getId()).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.lenient().when(professional.getShowPosition()).thenReturn(showPosition);
        Client client = mock(Client.class);
        org.mockito.Mockito.lenient().when(client.getId()).thenReturn(clientId);
        Attendance head = mock(Attendance.class);
        org.mockito.Mockito.lenient().when(head.getId()).thenReturn(UUID.randomUUID());
        when(head.getProfessional()).thenReturn(professional);
        when(head.getClient()).thenReturn(client);
        org.mockito.Mockito.lenient().when(head.isNextNotified()).thenReturn(false);
        return head;
    }

    @Test
    @DisplayName("posicao exposta (padrao): o push diz que o cliente e o proximo")
    void showPosition_saysNext() {
        Attendance head = headOf(true);
        when(attendanceRepository.findWaitingQueueByEstablishment(establishmentId)).thenReturn(List.of(head));

        service.notifyNextInLine(establishmentId);

        verify(pushPublisher).notifyUser(eq(clientId), anyString(), eq("Você é o próximo da fila."), anyMap());
        verify(head).setNextNotified(true);
    }

    @Test
    @DisplayName("posicao escondida pelo profissional: o aviso sai, sem revelar a posicao")
    void hidePosition_doesNotRevealPosition() {
        Attendance head = headOf(false);
        when(attendanceRepository.findWaitingQueueByEstablishment(establishmentId)).thenReturn(List.of(head));

        service.notifyNextInLine(establishmentId);

        verify(pushPublisher).notifyUser(eq(clientId), anyString(),
                eq("Fique por perto: seu atendimento está para começar."), anyMap());
        verify(pushPublisher, never()).notifyUser(any(), anyString(), eq("Você é o próximo da fila."), anyMap());
    }

    @Test
    @DisplayName("agendamento aceito para daqui a mais de 1 h (outro dia ou mais tarde): sem \"E quase a sua vez\" e sem marcar como avisado")
    void farScheduled_isNotNotified() {
        Attendance head = headOf(true);
        when(head.getScheduledTime()).thenReturn(java.time.OffsetDateTime.now().plusDays(1));
        when(attendanceRepository.findWaitingQueueByEstablishment(establishmentId)).thenReturn(List.of(head));

        service.notifyNextInLine(establishmentId);

        verify(pushPublisher, never()).notifyUser(any(), anyString(), anyString(), anyMap());
        verify(head, never()).setNextNotified(true);
    }

    @Test
    @DisplayName("horario perto (ate 1 h) ou ja chegado: o aviso sai")
    void nearScheduled_isNotified() {
        Attendance head = headOf(true);
        when(head.getScheduledTime()).thenReturn(java.time.OffsetDateTime.now().plusMinutes(20));
        when(attendanceRepository.findWaitingQueueByEstablishment(establishmentId)).thenReturn(List.of(head));

        service.notifyNextInLine(establishmentId);

        verify(pushPublisher).notifyUser(eq(clientId), anyString(), eq("Você é o próximo da fila."), anyMap());
    }

    @Test
    @DisplayName("isFarAhead: so passa de 1 h e sem horario conta como perto")
    void isFarAhead() {
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        org.assertj.core.api.Assertions.assertThat(QueuePushService.isFarAhead(null, now)).isFalse();
        org.assertj.core.api.Assertions.assertThat(QueuePushService.isFarAhead(now.plusMinutes(60), now)).isFalse();
        org.assertj.core.api.Assertions.assertThat(QueuePushService.isFarAhead(now.plusMinutes(61), now)).isTrue();
        org.assertj.core.api.Assertions.assertThat(QueuePushService.isFarAhead(now.minusMinutes(5), now)).isFalse();
    }
}
