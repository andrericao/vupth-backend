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
        when(professional.getShowPosition()).thenReturn(showPosition);
        Client client = mock(Client.class);
        when(client.getId()).thenReturn(clientId);
        Attendance head = mock(Attendance.class);
        when(head.getId()).thenReturn(UUID.randomUUID());
        when(head.getProfessional()).thenReturn(professional);
        when(head.getClient()).thenReturn(client);
        when(head.isNextNotified()).thenReturn(false);
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
}
