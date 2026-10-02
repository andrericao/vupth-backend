package com.ospaulos.vupth.unit.client;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent;
import com.ospaulos.vupth.domain.client.model.PenaltyType;
import com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository;
import com.ospaulos.vupth.domain.client.service.LateArrivalService;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests — contador de atrasos (D4, Andre 25/09): soma entre atendimentos, zera quando a multa
 * nasce (3o atraso = R$ 5,00), e so conta a marcacao feita 5 min depois do horario comunicado.
 */
@ExtendWith(MockitoExtension.class)
class LateArrivalServiceTest {

    @Mock ClientPenaltyEventRepository penaltyEventRepository;
    @Mock PushNotificationPublisher pushPublisher;
    @InjectMocks LateArrivalService service;

    private static final OffsetDateTime NOW = BusinessClock.at(LocalDate.parse("2026-09-25"), LocalTime.parse("15:00"));

    Client client;
    Professional professional;
    List<Attendance> professionalAttendances;

    @BeforeEach
    void setup() {
        client = new Client();
        client.setId(UUID.randomUUID());
        professional = mock(Professional.class);
        professionalAttendances = new ArrayList<>();
        lenient().when(professional.getAttendances()).thenReturn(professionalAttendances);
        lenient().when(professional.getIntervals()).thenReturn(List.of());
    }

    private Attendance attendance(OffsetDateTime promised) {
        Attendance a = new Attendance();
        a.setId(UUID.randomUUID());
        a.setClient(client);
        a.setPromisedTime(promised);
        return a;
    }

    /** O profissional concluiu um corte as {@code endTime} — a vez do proximo chegou ai. */
    private void finishedAt(OffsetDateTime endTime) {
        Attendance done = new Attendance();
        done.setStatus(AttendanceStatus.FINISHED);
        done.setEndTime(endTime);
        professionalAttendances.add(done);
    }

    @Test
    @DisplayName("marcado 4 min depois do horario prometido: nao conta (tolerancia de 5 min)")
    void withinTolerance_notCounted() {
        int n = service.registerLate(attendance(NOW.minusMinutes(4)), professional, NOW);

        assertThat(n).isZero();
        assertThat(client.getLateCount()).isZero();
        verifyNoInteractions(pushPublisher);
    }

    @Test
    @DisplayName("marcado 5 min depois do prometido: conta, e o cliente e avisado ('seu 1o atraso')")
    void afterTolerance_countedAndNotified() {
        int n = service.registerLate(attendance(NOW.minusMinutes(5)), professional, NOW);

        assertThat(n).isEqualTo(1);
        assertThat(client.getLateCount()).isEqualTo(1);
        assertThat(client.getLatesUntilPenalty()).isEqualTo(2);
        verify(pushPublisher).notifyUser(eq(client.getId()), any(), contains("1º atraso"), any());
    }

    @Test
    @DisplayName("cascata: prometido ha muito, mas a vez so chegou agora (fila atrasou) -> ainda nao conta")
    void turnArrivedLater_countsFromTurn() {
        finishedAt(NOW.minusMinutes(2)); // o profissional ficou livre ha 2 min

        int n = service.registerLate(attendance(NOW.minusMinutes(40)), professional, NOW);

        assertThat(n).as("5 min contam da chegada da vez, nao da promessa").isZero();
    }

    @Test
    @DisplayName("fila andou mais rapido: a vez chegou cedo, mas vale a promessa -> nao conta antes dela")
    void promisedLater_countsFromPromise() {
        finishedAt(NOW.minusMinutes(30)); // livre ha 30 min, mas prometeram para daqui a 10

        int n = service.registerLate(attendance(NOW.plusMinutes(10)), professional, NOW);

        assertThat(n).isZero();
    }

    @Test
    @DisplayName("soma entre atendimentos e o 3o gera a multa de R$ 5,00, zerando o contador")
    void thirdLate_penaltyAndReset() {
        client.setLateCount(2); // dois atrasos de outros cortes

        Attendance a = attendance(NOW.minusMinutes(10));
        int n = service.registerLate(a, professional, NOW);

        assertThat(n).isEqualTo(3);
        assertThat(client.getLateCount()).as("zera quando a multa nasce").isZero();
        ArgumentCaptor<ClientPenaltyEvent> event = ArgumentCaptor.forClass(ClientPenaltyEvent.class);
        verify(penaltyEventRepository).save(event.capture());
        assertThat(event.getValue().getType()).isEqualTo(PenaltyType.LATE_ARRIVAL);
        assertThat(event.getValue().getAmount()).isEqualByComparingTo("5.00");
        assertThat(event.getValue().getAttendanceId()).isEqualTo(a.getId());
    }

    @Test
    @DisplayName("undo-late desconta so o que contou; marcacao dentro da tolerancia nao mexe no contador")
    void undo_onlyCountedLates() {
        client.setLateCount(1);
        Attendance early = attendance(NOW.plusMinutes(1));
        service.registerLate(early, professional, NOW); // nao conta

        service.undoLate(early);

        assertThat(client.getLateCount()).isEqualTo(1);
        verifyNoInteractions(penaltyEventRepository);
    }

    @Test
    @DisplayName("undo-late do atraso que gerou a multa (ainda nao cobrada): a multa sai e o contador volta a 2")
    void undo_penaltyFromThisAttendance_removed() {
        client.setLateCount(2);
        Attendance a = attendance(NOW.minusMinutes(10));
        service.registerLate(a, professional, NOW);
        ClientPenaltyEvent fine = new ClientPenaltyEvent(client, PenaltyType.LATE_ARRIVAL, a.getId(), 0,
                new java.math.BigDecimal("5.00"));
        when(penaltyEventRepository.findByClientIdOrderByCreatedAtDesc(client.getId())).thenReturn(List.of(fine));

        service.undoLate(a);

        verify(penaltyEventRepository).delete(fine);
        assertThat(client.getLateCount()).isEqualTo(2);
        assertThat(a.getCountedLates()).isZero();
    }

    @Test
    @DisplayName("convidado (sem conta) nao tem contador")
    void guest_ignored() {
        Attendance guest = attendance(NOW.minusMinutes(30));
        guest.setClient(null);

        assertThat(service.registerLate(guest, professional, NOW)).isZero();
        verifyNoInteractions(penaltyEventRepository, pushPublisher);
    }
}
