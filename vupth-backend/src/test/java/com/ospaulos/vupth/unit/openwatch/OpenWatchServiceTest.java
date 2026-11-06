package com.ospaulos.vupth.unit.openwatch;

import com.ospaulos.vupth.domain.attendance.event.AttendanceStartedEvent;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.openwatch.exception.OpenWatchException;
import com.ospaulos.vupth.domain.openwatch.model.OpenWatch;
import com.ospaulos.vupth.domain.openwatch.model.OpenWatchKind;
import com.ospaulos.vupth.domain.openwatch.repository.OpenWatchRepository;
import com.ospaulos.vupth.domain.openwatch.service.OpenWatchService;
import com.ospaulos.vupth.domain.professional.event.WorkStatusChangedEvent;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** avisar-quando-abrir (BAK-8): regras do servico sem banco. */
class OpenWatchServiceTest {

    private final OpenWatchRepository repository = mock(OpenWatchRepository.class);
    private final EstablishmentRepository establishments = mock(EstablishmentRepository.class);
    private final ProfessionalRepository professionals = mock(ProfessionalRepository.class);
    private final AttendanceRepository attendances = mock(AttendanceRepository.class);
    private final PushNotificationPublisher push = mock(PushNotificationPublisher.class);
    private final OpenWatchService service =
            new OpenWatchService(repository, establishments, professionals, attendances, push);

    private final UUID user = UUID.randomUUID();
    private final UUID est = UUID.randomUUID();
    private final UUID prof = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Establishment establishment = new Establishment();
        establishment.setName("Barbearia X");
        when(establishments.findById(est)).thenReturn(Optional.of(establishment));
        Professional professional = mock(Professional.class);
        when(professional.getName()).thenReturn("Beto");
        when(professionals.findByIdWithBaseUser(prof)).thenReturn(Optional.of(professional));
        when(repository.save(any(OpenWatch.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("fila andou: o primeiro atendimento iniciado avisa quem pediu QUEUE_MOVING daquele profissional ou da loja")
    void queueMovingFires() {
        OpenWatch forProf = new OpenWatch(user, est, prof, OpenWatchKind.QUEUE_MOVING, BusinessClock.today());
        OpenWatch forOther = new OpenWatch(UUID.randomUUID(), est, other, OpenWatchKind.QUEUE_MOVING, BusinessClock.today());
        when(repository.findByEstablishmentIdAndKindAndDayAndFiredAtIsNull(est, OpenWatchKind.QUEUE_MOVING, BusinessClock.today()))
                .thenReturn(List.of(forProf, forOther));
        Attendance attendance = new Attendance();
        Establishment e = mock(Establishment.class);
        when(e.getId()).thenReturn(est);
        Professional p = mock(Professional.class);
        when(p.getId()).thenReturn(prof);
        attendance.setEstablishment(e);
        attendance.setProfessional(p);
        UUID attendanceId = UUID.randomUUID();
        when(attendances.findByIdEager(attendanceId)).thenReturn(Optional.of(attendance));

        service.onAttendanceStarted(new AttendanceStartedEvent(attendanceId));

        verify(push).notifyUser(eq(user), eq("A fila começou a andar"), any(), anyMap());
        verify(push, never()).notifyUser(eq(forOther.getUserId()), any(), any(), anyMap());
        assertThat(forProf.getFiredAt()).isNotNull();
        assertThat(forOther.getFiredAt()).isNull();
    }

    @Test
    @DisplayName("atendimento sem loja ou sem profissional, ou inexistente: nada sai; falha no push nao propaga")
    void queueMovingGuards() {
        UUID missing = UUID.randomUUID();
        when(attendances.findByIdEager(missing)).thenReturn(Optional.empty());
        service.onAttendanceStarted(new AttendanceStartedEvent(missing));
        UUID broken = UUID.randomUUID();
        when(attendances.findByIdEager(broken)).thenThrow(new RuntimeException("db"));
        service.onAttendanceStarted(new AttendanceStartedEvent(broken));
        verifyNoInteractions(push);
    }

    @Test
    @DisplayName("fim de expediente, sem loja ou sem pedido: nada sai")
    void openGuards() {
        service.onWorkStatusChanged(new WorkStatusChangedEvent(prof, est, ProfessionalStatus.WORKING, ProfessionalStatus.OFF, OffsetDateTime.now()));
        service.onWorkStatusChanged(new WorkStatusChangedEvent(prof, null, ProfessionalStatus.OFF, ProfessionalStatus.WORKING, OffsetDateTime.now()));
        when(repository.findByEstablishmentIdAndKindAndDayAndFiredAtIsNull(est, OpenWatchKind.NOTIFY_OPEN, BusinessClock.today()))
                .thenReturn(List.of());
        service.onWorkStatusChanged(new WorkStatusChangedEvent(prof, est, ProfessionalStatus.OFF, ProfessionalStatus.WORKING, OffsetDateTime.now()));
        verifyNoInteractions(push);
    }

    @Test
    @DisplayName("teto de 20 pedidos por dia: o 21o e 429 OPEN_WATCH_LIMIT; profissional inexistente e 422")
    void limitsAndValidation() {
        List<OpenWatch> twenty = new ArrayList<>();
        for (int i = 0; i < OpenWatchService.MAX_PER_DAY; i++) {
            twenty.add(new OpenWatch(user, UUID.randomUUID(), null, OpenWatchKind.NOTIFY_OPEN, BusinessClock.today()));
        }
        when(repository.findByUserIdAndDayOrderByCreatedAtAsc(user, BusinessClock.today())).thenReturn(twenty);
        assertThatThrownBy(() -> service.subscribe(user, est, null, OpenWatchKind.NOTIFY_OPEN))
                .isInstanceOf(OpenWatchException.class)
                .extracting("code").isEqualTo("OPEN_WATCH_LIMIT");

        when(professionals.findById(other)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.subscribe(user, est, other, OpenWatchKind.NOTIFY_OPEN))
                .extracting("code").isEqualTo("OPEN_WATCH_PROFESSIONAL_INVALID");
    }

    @Test
    @DisplayName("cancelar e listar")
    void cancelAndList() {
        OpenWatch mine = new OpenWatch(user, est, null, OpenWatchKind.NOTIFY_OPEN, BusinessClock.today());
        when(repository.findById(mine.getId())).thenReturn(Optional.of(mine));
        assertThatThrownBy(() -> service.cancel(UUID.randomUUID(), mine.getId()))
                .extracting("code").isEqualTo("OPEN_WATCH_NOT_FOUND");
        service.cancel(user, mine.getId());
        verify(repository).delete(mine);
        when(repository.findByUserIdAndDayOrderByCreatedAtAsc(user, BusinessClock.today())).thenReturn(List.of(mine));
        assertThat(service.listToday(user)).containsExactly(mine);
    }
}
