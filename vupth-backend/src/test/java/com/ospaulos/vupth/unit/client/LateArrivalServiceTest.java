package com.ospaulos.vupth.unit.client;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.client.service.FirstAttendanceExemption;
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
    @Mock FirstAttendanceExemption firstAttendanceExemption;
    @Mock com.ospaulos.vupth.domain.client.service.ClientPenaltyService clientPenaltyService;
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

    // ─── testes-de-pagamento-massivos (DEC-92): os cantos do contador ───

    @Test
    @DisplayName("sem promessa e sem a vez ter chegado: nada comunicado, a marcacao conta; sem promessa mas com a vez chegada, vale a vez")
    void nothingCommunicated_counts() {
        assertThat(service.registerLate(attendance(null), professional, NOW)).isEqualTo(1);

        finishedAt(NOW.minusMinutes(10));
        assertThat(service.registerLate(attendance(null), professional, NOW)).isEqualTo(2);
        // a vez chegou ha 3 min: dentro da tolerancia de 5, nao conta
        assertThat(service.registerLate(attendance(null), professional, NOW.minusMinutes(7))).isZero();
    }

    @Test
    @DisplayName("noShowAvailableAt: 10 min depois da hora comunicada; sem hora comunicada, do horario marcado")
    void noShowAvailableAt() {
        Attendance promised = attendance(NOW.minusMinutes(30));
        assertThat(service.noShowAvailableAt(promised, professional, NOW)).isEqualTo(NOW.minusMinutes(20));

        Attendance onlyScheduled = attendance(null);
        onlyScheduled.setScheduledTime(NOW.minusHours(1));
        assertThat(service.noShowAvailableAt(onlyScheduled, professional, NOW)).isEqualTo(NOW.minusMinutes(50));
    }

    @Test
    @DisplayName("desfazer: convidado ou sem atraso contado nao mexe; sem multa, devolve os atrasos; com a multa ainda nao cobrada, ela sai e o contador volta")
    void undoLate() {
        Attendance guest = attendance(NOW);
        guest.setClient(null);
        guest.setCountedLates(1);
        service.undoLate(guest);
        verifyNoInteractions(penaltyEventRepository);

        Attendance none = attendance(NOW);
        none.setCountedLates(0);
        service.undoLate(none);
        verifyNoInteractions(penaltyEventRepository);

        client.setLateCount(2);
        Attendance two = attendance(NOW);
        two.setCountedLates(2);
        when(penaltyEventRepository.findByClientIdOrderByCreatedAtDesc(client.getId())).thenReturn(List.of());
        service.undoLate(two);
        assertThat(client.getLateCount()).isZero();
        assertThat(two.getCountedLates()).isZero();

        // o contador nunca fica negativo
        client.setLateCount(0);
        Attendance more = attendance(NOW);
        more.setCountedLates(1);
        service.undoLate(more);
        assertThat(client.getLateCount()).isZero();

        // a multa do 3o atraso (ainda nao cobrada) sai: antes dele o cliente estava em (atual + 3 - contados)
        client.setLateCount(0);
        Attendance third = attendance(NOW);
        third.setCountedLates(1);
        ClientPenaltyEvent penalty = new ClientPenaltyEvent(client, PenaltyType.LATE_ARRIVAL, third.getId(), 0, new java.math.BigDecimal("5.00"));
        ClientPenaltyEvent other = new ClientPenaltyEvent(client, PenaltyType.LATE_CANCEL, third.getId(), 0, new java.math.BigDecimal("5.00"));
        ClientPenaltyEvent elsewhere = new ClientPenaltyEvent(client, PenaltyType.LATE_ARRIVAL, UUID.randomUUID(), 0, new java.math.BigDecimal("5.00"));
        ClientPenaltyEvent charged = new ClientPenaltyEvent(client, PenaltyType.LATE_ARRIVAL, third.getId(), 0, new java.math.BigDecimal("5.00"));
        charged.setChargedAttendanceId(UUID.randomUUID());
        when(penaltyEventRepository.findByClientIdOrderByCreatedAtDesc(client.getId()))
                .thenReturn(List.of(other, elsewhere, charged, penalty));
        service.undoLate(third);
        verify(penaltyEventRepository).delete(penalty);
        verify(penaltyEventRepository, never()).delete(charged);
        assertThat(client.getLateCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a vez so chega no que ja aconteceu: um corte concluido com horario no futuro nao conta para a hora comunicada")
    void futureFinishIsIgnored() {
        finishedAt(NOW.plusMinutes(30));

        assertThat(service.registerLate(attendance(null), professional, NOW)).isEqualTo(1);
    }

    // ─── mutacao-pit-no-dinheiro (DEC-99) ───

    @Test
    @DisplayName("PIT: so conta a vez de corte CONCLUIDO e de HOJE; refeicao encerrada hoje tambem conta, a nao feita nao")
    void turnArrivedOnlyFromFinishedToday() {
        Attendance scheduled = attendance(null);
        scheduled.setScheduledTime(NOW.minusHours(2));

        // em andamento com horario de fim hoje: nao e a vez de ninguem
        Attendance running = new Attendance();
        running.setStatus(AttendanceStatus.IN_PROGRESS);
        running.setEndTime(NOW.minusMinutes(1));
        professionalAttendances.add(running);
        // concluido ontem: nao conta hoje
        finishedAt(NOW.minusDays(1));
        assertThat(service.noShowAvailableAt(scheduled, professional, NOW)).isEqualTo(NOW.minusMinutes(110));

        // refeicao encerrada hoje conta; a que nao foi feita hoje, nao
        var done = mock(com.ospaulos.vupth.domain.professional.model.ProfessionalInterval.class);
        when(done.isDoneOn(NOW.toLocalDate())).thenReturn(true);
        when(done.getActualEndTime()).thenReturn(NOW.minusMinutes(3));
        var notDone = mock(com.ospaulos.vupth.domain.professional.model.ProfessionalInterval.class);
        when(notDone.isDoneOn(any())).thenReturn(false);
        lenient().when(notDone.getActualEndTime()).thenReturn(NOW.minusMinutes(1)); // se entrasse, a hora comunicada mudaria
        when(professional.getIntervals()).thenReturn(List.of(done, notDone));
        assertThat(service.noShowAvailableAt(scheduled, professional, NOW)).isEqualTo(NOW.plusMinutes(7));
    }

    @Test
    @DisplayName("PIT: o aviso do 1o, do 2o e do 3o atraso: texto, quantos faltam para a multa e se foi para o fim da fila")
    void pushTextAndData() {
        for (int count = 1; count <= 3; count++) {
            client.setLateCount(count - 1);
            clearInvocations(pushPublisher);

            service.registerLate(attendance(NOW.minusMinutes(10)), professional, NOW);

            ArgumentCaptor<java.util.Map<String, Object>> data = ArgumentCaptor.forClass(java.util.Map.class);
            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(pushPublisher).notifyUser(eq(client.getId()), any(), body.capture(), data.capture());
            assertThat(data.getValue().get("lateCount")).isEqualTo(String.valueOf(count));
            assertThat(data.getValue().get("latesUntilPenalty")).isEqualTo(String.valueOf(3 - count));
            assertThat(data.getValue().get("removedFromQueue")).isEqualTo("false");
            assertThat(data.getValue().get("movedToEnd")).isEqualTo(String.valueOf(count == 3));
            if (count == 3) {
                assertThat(body.getValue()).contains("Você foi para o fim da fila");
            } else {
                assertThat(body.getValue()).contains("No 3º, multa de R$ 5,00 e você vai para o fim da fila");
            }
        }
    }

    @Test
    @DisplayName("nao-chegou-tres-atrasos (opcao A): sem pagar, a 2a multa so vem depois de mais 3 atrasos")
    void secondFineNeedsThreeMoreLates() {
        client.setLateCount(2);
        service.registerLate(attendance(NOW.minusMinutes(30)), professional, NOW, true); // 3o: multa, contador a zero
        assertThat(client.getLateCount()).isZero();
        verify(penaltyEventRepository, times(1)).save(any(ClientPenaltyEvent.class));

        service.registerLate(attendance(NOW.minusMinutes(30)), professional, NOW, true); // 1o do novo ciclo
        service.registerLate(attendance(NOW.minusMinutes(30)), professional, NOW, true); // 2o
        assertThat(client.getLateCount()).isEqualTo(2);
        verify(penaltyEventRepository, times(1)).save(any(ClientPenaltyEvent.class));

        service.registerLate(attendance(NOW.minusMinutes(30)), professional, NOW, true); // 3o do novo ciclo: 2a multa
        verify(penaltyEventRepository, times(2)).save(any(ClientPenaltyEvent.class));
    }

    @Test
    @DisplayName("nao-chegou-tres-atrasos: com duas multas pendentes (DEC-83) o 3o atraso nao gera multa nova, mas o contador zera")
    void thirdLateAtThePendingLimit() {
        client.setLateCount(2);
        when(clientPenaltyService.pendingFor(client.getId()))
                .thenReturn(List.of(mock(ClientPenaltyEvent.class), mock(ClientPenaltyEvent.class)));

        int n = service.registerLate(attendance(NOW.minusMinutes(30)), professional, NOW, true);

        assertThat(n).isEqualTo(3);
        assertThat(client.getLateCount()).isZero();
        verify(penaltyEventRepository, never()).save(any(ClientPenaltyEvent.class));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(pushPublisher).notifyUser(eq(client.getId()), any(), body.capture(), any());
        assertThat(body.getValue()).contains("sem multa nova").contains("saiu da fila");
    }

    @Test
    @DisplayName("nao-chegou-tres-atrasos: o aviso do 'Nao chegou' diz 'n de 3' e, no 3o, que saiu da fila (removedFromQueue); o atraso a mao nao")
    void noShowNotificationSaysTheExit() {
        for (int count = 1; count <= 3; count++) {
            client.setLateCount(count - 1);
            clearInvocations(pushPublisher);

            service.registerLate(attendance(NOW.minusMinutes(10)), professional, NOW, true);

            ArgumentCaptor<java.util.Map<String, Object>> data = ArgumentCaptor.forClass(java.util.Map.class);
            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(pushPublisher).notifyUser(eq(client.getId()), any(), body.capture(), data.capture());
            assertThat(body.getValue()).contains(count + " de 3");
            assertThat(data.getValue().get("removedFromQueue")).isEqualTo(String.valueOf(count == 3));
            assertThat(data.getValue().get("movedToEnd")).isEqualTo("false");
            if (count == 3) {
                assertThat(body.getValue()).contains("saiu da fila").contains("Pague a multa para zerar a contagem");
            } else {
                assertThat(body.getValue()).contains("você sai da fila");
            }
        }
    }

    @Test
    @DisplayName("nao-chegou-tres-atrasos: pagar a multa de atraso zera o contador; sem contador ou sem cliente, nada muda")
    void paidPenaltyResetsTheCounter() {
        client.setLateCount(3);
        service.resetOnPenaltyPaid(client);
        assertThat(client.getLateCount()).isZero();

        service.resetOnPenaltyPaid(client);
        service.resetOnPenaltyPaid(null);
        assertThat(client.getLateCount()).isZero();
    }

    @Test
    @DisplayName("DEC-104: sem nenhum atendimento finalizado, o atraso nao conta, nao avisa e nao entra no contador")
    void firstAttendance_neverCounted() {
        when(firstAttendanceExemption.applies(client.getId())).thenReturn(true);
        client.setLateCount(2);

        int n = service.registerLate(attendance(NOW.minusMinutes(30)), professional, NOW);

        assertThat(n).isZero();
        assertThat(client.getLateCount()).as("o contador nao anda").isEqualTo(2);
        verifyNoInteractions(pushPublisher);
        verifyNoInteractions(penaltyEventRepository);
    }

    @Test
    @DisplayName("DEC-104: depois do primeiro concluido a regra volta — o 3o atraso gera a multa de R$ 5")
    void afterFirstFinished_thirdLateFines() {
        client.setLateCount(2);

        int n = service.registerLate(attendance(NOW.minusMinutes(30)), professional, NOW);

        assertThat(n).isEqualTo(3);
        ArgumentCaptor<ClientPenaltyEvent> event = ArgumentCaptor.forClass(ClientPenaltyEvent.class);
        verify(penaltyEventRepository).save(event.capture());
        assertThat(event.getValue().getType()).isEqualTo(PenaltyType.LATE_ARRIVAL);
    }
}
