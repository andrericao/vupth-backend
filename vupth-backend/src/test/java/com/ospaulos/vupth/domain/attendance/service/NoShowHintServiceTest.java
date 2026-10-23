package com.ospaulos.vupth.domain.attendance.service;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.client.service.LateArrivalService;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("regra-do-atraso-na-fila-vazia (DEC-101): o profissional so e avisado no caso certo")
class NoShowHintServiceTest {

    private final AttendanceRepository repository = mock(AttendanceRepository.class);
    private final LateArrivalService late = mock(LateArrivalService.class);
    private final PushNotificationPublisher push = mock(PushNotificationPublisher.class);
    private final NoShowHintService service = new NoShowHintService(repository, late, push);

    private final UUID professionalId = UUID.randomUUID();
    private final UUID establishmentId = UUID.randomUUID();
    private Professional professional;
    private Establishment establishment;

    @BeforeEach
    void setUp() {
        professional = mock(Professional.class);
        when(professional.getId()).thenReturn(professionalId);
        establishment = mock(Establishment.class);
        when(establishment.getId()).thenReturn(establishmentId);
        when(late.noShowWaitMinutes()).thenReturn(10);
    }

    private Attendance attendance() {
        Attendance a = mock(Attendance.class);
        when(a.getId()).thenReturn(UUID.randomUUID());
        when(a.getProfessional()).thenReturn(professional);
        when(a.getEstablishment()).thenReturn(establishment);
        when(a.getGuestName()).thenReturn("Cliente da vez");
        return a;
    }

    private void queue(Attendance newcomer, Attendance... waiting) {
        when(repository.findByIdEager(newcomer.getId())).thenReturn(Optional.of(newcomer));
        when(repository.findWaitingQueueByProfessionalAndEstablishment(professionalId, establishmentId))
                .thenReturn(List.of(waiting));
    }

    @Test
    @DisplayName("um so cliente esperando, atrasado ha mais de 10 min, e outro entra: avisa o profissional")
    void notifiesInTheRightCase() {
        Attendance lateOne = attendance();
        Attendance newcomer = attendance();
        queue(newcomer, lateOne, newcomer);
        when(late.noShowAvailableAt(eq(lateOne), eq(professional), any())).thenReturn(OffsetDateTime.now().minusMinutes(1));

        assertThat(service.onQueueEntry(newcomer.getId())).isTrue();

        String lateId = lateOne.getId().toString();
        verify(push).notifyUser(eq(professionalId), eq("Cliente atrasado e fila andando"), anyString(),
                eq(Map.of("type", "NO_SHOW_AVAILABLE", "attendanceId", lateId,
                        "establishmentId", establishmentId.toString())));
    }

    @Test
    @DisplayName("ainda dentro dos 10 min: nao avisa")
    void tooEarlyDoesNotNotify() {
        Attendance lateOne = attendance();
        Attendance newcomer = attendance();
        queue(newcomer, lateOne, newcomer);
        when(late.noShowAvailableAt(eq(lateOne), eq(professional), any())).thenReturn(OffsetDateTime.now().plusMinutes(3));

        assertThat(service.onQueueEntry(newcomer.getId())).isFalse();
        verify(push, never()).notifyUser(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("fila vazia (so o novo) ou com mais de um esperando: nao avisa")
    void otherQueueShapesDoNotNotify() {
        Attendance newcomer = attendance();
        queue(newcomer, newcomer);
        assertThat(service.onQueueEntry(newcomer.getId())).isFalse();

        Attendance a = attendance();
        Attendance b = attendance();
        Attendance c = attendance();
        queue(c, a, b, c);
        assertThat(service.onQueueEntry(c.getId())).isFalse();

        verify(push, never()).notifyUser(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("atendimento que nao existe ou falha ao consultar: nao avisa e nao lanca")
    void failuresAreSwallowed() {
        assertThat(service.onQueueEntry(UUID.randomUUID())).isFalse();

        Attendance newcomer = attendance();
        when(repository.findByIdEager(newcomer.getId())).thenThrow(new IllegalStateException("banco fora"));
        assertThat(service.onQueueEntry(newcomer.getId())).isFalse();
    }
}
