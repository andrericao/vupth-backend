package com.ospaulos.vupth.unit.attendance;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.attendance.service.EarlierTurnService;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.queue.service.ClientWaitCalculator;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import com.ospaulos.vupth.infrastructure.websocket.QueueWebSocketPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests — quem sobe por desistencia e avisado do horario novo e so perde a protecao se aceitar
 * (Andre 17/09, D8 do Client).
 */
@ExtendWith(MockitoExtension.class)
class EarlierTurnServiceTest {

    @Mock AttendanceRepository attendanceRepository;
    @Mock ClientWaitCalculator clientWaitCalculator;
    @Mock QueueWebSocketPublisher queuePublisher;
    @Mock PushNotificationPublisher pushPublisher;
    @InjectMocks EarlierTurnService service;

    final UUID professionalId = UUID.randomUUID();
    final UUID establishmentId = UUID.randomUUID();
    Establishment establishment;

    @BeforeEach
    void setup() {
        establishment = new Establishment();
        establishment.setId(establishmentId);
    }

    private Attendance waiting(OffsetDateTime promised, int waitNow) {
        Client client = new Client();
        client.setId(UUID.randomUUID());
        Attendance a = new Attendance();
        a.setId(UUID.randomUUID());
        a.setClient(client);
        a.setEstablishment(establishment);
        a.setStatus(AttendanceStatus.SCHEDULED);
        a.setPromisedTime(promised);
        lenient().when(clientWaitCalculator.clientWaitMinutes(eq(a), any())).thenReturn(OptionalInt.of(waitNow));
        return a;
    }

    @Test
    @DisplayName("vez adiantou 30 min e ficou a menos de 1h: avisa com o horario novo")
    void advancedIntoPenaltyZone_offered() {
        Attendance b = waiting(OffsetDateTime.now().plusMinutes(40), 10);
        when(attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(professionalId, establishmentId))
                .thenReturn(List.of(b));

        service.offerEarlierTurns(professionalId, establishmentId);

        verify(pushPublisher).notifyUser(eq(b.getClient().getId()), any(), contains("adiantou"), anyMap());
        verify(queuePublisher).notifyClient(eq(b.getClient().getId()), argThat(n -> n.type().equals("EARLIER_TURN_OFFERED")));
    }

    @Test
    @DisplayName("adiantou menos de 5 min: e ritmo da fila, nao avisa")
    void smallAdvance_notOffered() {
        Attendance b = waiting(OffsetDateTime.now().plusMinutes(12), 10);
        when(attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(professionalId, establishmentId))
                .thenReturn(List.of(b));

        service.offerEarlierTurns(professionalId, establishmentId);

        verifyNoInteractions(pushPublisher, queuePublisher);
    }

    @Test
    @DisplayName("ainda a 1h ou mais da vez: fora da zona de multa, nao avisa")
    void stillFarAway_notOffered() {
        Attendance b = waiting(OffsetDateTime.now().plusMinutes(120), 70);
        when(attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(professionalId, establishmentId))
                .thenReturn(List.of(b));

        service.offerEarlierTurns(professionalId, establishmentId);

        verifyNoInteractions(pushPublisher, queuePublisher);
    }

    @Test
    @DisplayName("aceitar: o horario novo vira o prometido")
    void accept_movesPromisedTime() {
        Attendance b = waiting(OffsetDateTime.now().plusMinutes(40), 10);
        when(attendanceRepository.findByIdEager(b.getId())).thenReturn(Optional.of(b));

        OffsetDateTime promised = service.acceptEarlierTurn(b.getId(), b.getClient().getId());

        assertThat(promised).isBetween(OffsetDateTime.now().plusMinutes(9), OffsetDateTime.now().plusMinutes(11));
        assertThat(b.getPromisedTime()).isEqualTo(promised);
        verify(attendanceRepository).save(b);
    }

    @Test
    @DisplayName("aceitar pelo atendimento de outro cliente: 403")
    void accept_otherClient_denied() {
        Attendance b = waiting(OffsetDateTime.now().plusMinutes(40), 10);
        when(attendanceRepository.findByIdEager(b.getId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.acceptEarlierTurn(b.getId(), UUID.randomUUID()))
                .isInstanceOf(EstablishmentAccessDeniedException.class);
    }
}
