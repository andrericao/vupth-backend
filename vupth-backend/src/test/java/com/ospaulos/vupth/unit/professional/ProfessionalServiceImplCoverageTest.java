package com.ospaulos.vupth.unit.professional;

import com.ospaulos.vupth.api.attendance.dto.request.AttendanceGuestRequest;
import com.ospaulos.vupth.api.queue.dto.response.QueueResponse;
import com.ospaulos.vupth.api.serviceItem.dto.request.ServiceItemRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceInvalidStatusException;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.attendance.service.AttendanceService;
import com.ospaulos.vupth.domain.client.service.LateArrivalService;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.service.EstablishmentOpenStatusService;
import com.ospaulos.vupth.domain.establishment.service.EstablishmentService;
import com.ospaulos.vupth.domain.professional.exception.NotAProfessionalException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.service.impl.ProfessionalIntervalService;
import com.ospaulos.vupth.domain.professional.service.impl.ProfessionalServiceImpl;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;
import com.ospaulos.vupth.domain.queue.service.QueueService;
import com.ospaulos.vupth.domain.serviceItem.exception.ServiceItemAlreadyExistsException;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.serviceItem.service.ServiceItemService;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ProfessionalServiceImpl — os caminhos que o {@link ProfessionalServiceImplTest} nao passava
 * (cobertura-98): a fila do profissional, o cancel pelo profissional, o 3o atraso indo para o fim,
 * o encaixe de quem esta sozinho, a drenagem sem loja e as guardas de servico e de papel.
 */
@ExtendWith(MockitoExtension.class)
class ProfessionalServiceImplCoverageTest {

    @Mock QueueService queueService;
    @Mock AttendanceService attendanceService;
    @Mock ProfessionalIntervalService intervalService;
    @Mock ServiceItemService serviceItemService;
    @Mock EstablishmentService establishmentService;
    @Mock ProfessionalRepository professionalRepository;
    @Mock AttendanceRepository attendanceRepository;
    @Mock EstablishmentRepository establishmentRepository;
    @Mock EstablishmentOpenStatusService openStatusService;
    @Mock LateArrivalService lateArrivalService;

    @InjectMocks ProfessionalServiceImpl professionalService;

    private UUID professionalId, attendanceId, establishmentId;
    private BaseUser profUser;
    private Professional professional;
    private Establishment establishment;
    private Attendance attendance;
    private QueueResponse overview;

    @BeforeEach
    void setUp() {
        professionalId = UUID.randomUUID();
        attendanceId = UUID.randomUUID();
        establishmentId = UUID.randomUUID();

        establishment = new Establishment();
        establishment.setId(establishmentId);
        establishment.setName("Barbershop Teste");

        profUser = new BaseUser();
        profUser.setId(professionalId);
        profUser.setFirstName("Carlos");
        profUser.addRole(RoleType.CLIENT);
        profUser.addRole(RoleType.PROFESSIONAL);

        professional = new Professional(profUser);
        professional.setEstablishment(establishment);
        professional.setIsAvailable(true);
        professional.setAttendances(new ArrayList<>());

        attendance = waiting(attendanceId, OffsetDateTime.now());
        overview = mock(QueueResponse.class);
    }

    private Attendance waiting(UUID id, OffsetDateTime scheduledTime) {
        Attendance a = new Attendance();
        a.setId(id);
        a.setProfessional(professional);
        a.setEstablishment(establishment);
        a.setStatus(AttendanceStatus.SCHEDULED);
        a.setScheduledTime(scheduledTime);
        a.setServiceItems(new ArrayList<>());
        return a;
    }

    private void loadAttendanceAndProfessional() {
        when(attendanceRepository.findByIdEager(attendanceId)).thenReturn(Optional.of(attendance));
        when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));
    }

    @Test
    @DisplayName("convidado: loja devolvida com outro id -> EstablishmentAccessDeniedException")
    void guest_establishmentMismatch() {
        Establishment other = new Establishment();
        other.setId(UUID.randomUUID());
        when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));
        when(establishmentRepository.findById(establishmentId)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> professionalService.createAndAddGuestAttendanceInQueue(
                new AttendanceGuestRequest(establishmentId, professionalId, null, "Ana")))
                .isInstanceOf(EstablishmentAccessDeniedException.class);
        verifyNoInteractions(attendanceService);
    }

    @Nested
    @DisplayName("getProfessionalQueue")
    class ProfessionalQueue {

        @Test
        @DisplayName("loja do profissional -> visao da fila")
        void ownEstablishment() {
            when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));
            when(queueService.getQueueOverview(professional, establishment)).thenReturn(overview);

            assertThat(professionalService.getProfessionalQueue(professionalId, establishmentId)).isSameAs(overview);
        }

        @Test
        @DisplayName("outra loja -> EstablishmentAccessDeniedException")
        void otherEstablishment() {
            when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));

            assertThatThrownBy(() -> professionalService.getProfessionalQueue(professionalId, UUID.randomUUID()))
                    .isInstanceOf(EstablishmentAccessDeniedException.class);
            verifyNoInteractions(queueService);
        }
    }

    @Nested
    @DisplayName("cancelAttendance pelo profissional")
    class CancelByProfessional {

        @Test
        @DisplayName("SCHEDULED -> cancela e devolve a fila")
        void scheduled() {
            loadAttendanceAndProfessional();
            when(queueService.getQueueOverview(professional, establishment)).thenReturn(overview);

            assertThat(professionalService.cancelAttendance(professionalId, attendanceId)).isSameAs(overview);
            verify(attendanceService).cancelAttendance(attendance);
        }

        @Test
        @DisplayName("em atendimento -> AttendanceInvalidStatusException")
        void inProgress() {
            attendance.setStatus(AttendanceStatus.IN_PROGRESS);
            loadAttendanceAndProfessional();

            assertThatThrownBy(() -> professionalService.cancelAttendance(professionalId, attendanceId))
                    .isInstanceOf(AttendanceInvalidStatusException.class);
            verify(attendanceService, never()).cancelAttendance(any(Attendance.class));
        }

        @Test
        @DisplayName("atendimento de outro profissional -> EstablishmentAccessDeniedException")
        void notOwner() {
            BaseUser otherUser = new BaseUser();
            otherUser.setId(UUID.randomUUID());
            attendance.setProfessional(new Professional(otherUser));
            loadAttendanceAndProfessional();

            assertThatThrownBy(() -> professionalService.cancelAttendance(professionalId, attendanceId))
                    .isInstanceOf(EstablishmentAccessDeniedException.class);
        }

        @Test
        @DisplayName("sem o papel PROFESSIONAL -> NotAProfessionalException")
        void withoutProfessionalRole() {
            profUser.removeRole(RoleType.PROFESSIONAL);
            loadAttendanceAndProfessional();

            assertThatThrownBy(() -> professionalService.cancelAttendance(professionalId, attendanceId))
                    .isInstanceOf(NotAProfessionalException.class);
        }
    }

    @Nested
    @DisplayName("markAttendanceLate — 3o atraso")
    class ThirdLate {

        @Test
        @DisplayName("vai para depois do ultimo e volta a SCHEDULED")
        void movesToEnd() {
            OffsetDateTime now = OffsetDateTime.now();
            Attendance last = waiting(UUID.randomUUID(), now.plusMinutes(30));
            loadAttendanceAndProfessional();
            when(attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(professionalId, establishmentId))
                    .thenReturn(List.of(attendance))
                    .thenReturn(List.of(attendance, last));
            when(lateArrivalService.registerLate(eq(attendance), eq(professional), any()))
                    .thenReturn(LateArrivalService.LATES_PER_PENALTY);

            professionalService.markAttendanceLate(professionalId, attendanceId);

            assertThat(attendance.getScheduledTime()).isEqualTo(last.getScheduledTime().plusMinutes(1));
            assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.SCHEDULED);
        }

        @Test
        @DisplayName("ja e o ultimo: fica onde esta")
        void alreadyLast() {
            OffsetDateTime original = attendance.getScheduledTime();
            loadAttendanceAndProfessional();
            when(attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(professionalId, establishmentId))
                    .thenReturn(List.of(attendance));
            when(lateArrivalService.registerLate(eq(attendance), eq(professional), any()))
                    .thenReturn(LateArrivalService.LATES_PER_PENALTY);

            professionalService.markAttendanceLate(professionalId, attendanceId);

            assertThat(attendance.getScheduledTime()).isEqualTo(original);
            assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.SCHEDULED);
        }

        @Test
        @DisplayName("fila vazia: nada a mover")
        void emptyQueue() {
            OffsetDateTime original = attendance.getScheduledTime();
            loadAttendanceAndProfessional();
            when(lateArrivalService.registerLate(eq(attendance), eq(professional), any()))
                    .thenReturn(LateArrivalService.LATES_PER_PENALTY);

            professionalService.markAttendanceLate(professionalId, attendanceId);

            assertThat(attendance.getScheduledTime()).isEqualTo(original);
        }
    }

    @Test
    @DisplayName("encaixe de quem esta sozinho (e LATE): sem ninguem a frente, ramp 0, volta a SCHEDULED")
    void fitIn_aloneAndLate() {
        attendance.setStatus(AttendanceStatus.LATE);
        loadAttendanceAndProfessional();
        when(attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(professionalId, establishmentId))
                .thenReturn(List.of(attendance));

        professionalService.fitInAttendance(professionalId, attendanceId, 2);

        assertThat(attendance.isFit()).isTrue();
        assertThat(attendance.getFitAheadLength()).isZero();
        assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.SCHEDULED);
    }

    @Nested
    @DisplayName("finishAttendance — drenagem")
    class Drain {

        @BeforeEach
        void inProgress() {
            attendance.setStatus(AttendanceStatus.IN_PROGRESS);
            professional.setAcceptingNew(false);
            professional.setWorkStatus(ProfessionalStatus.WORKING);
            when(queueService.getQueueOverview(any(), any())).thenReturn(overview);
        }

        @Test
        @DisplayName("ultimo atendimento sem loja: vira OFF sem recalcular a loja")
        void lastOne_withoutEstablishment() {
            attendance.setEstablishment(null);
            loadAttendanceAndProfessional();

            professionalService.finishAttendance(professionalId, attendanceId);

            assertThat(professional.getWorkStatus()).isEqualTo(ProfessionalStatus.OFF);
            verifyNoInteractions(openStatusService);
        }

        @Test
        @DisplayName("ainda ha fila: continua trabalhando")
        void stillHasQueue() {
            loadAttendanceAndProfessional();
            when(attendanceRepository.existsByProfessional_IdAndStatusIn(eq(professionalId), any())).thenReturn(true);

            professionalService.finishAttendance(professionalId, attendanceId);

            assertThat(professional.getWorkStatus()).isEqualTo(ProfessionalStatus.WORKING);
            verify(professionalRepository, never()).save(any());
        }

        @Test
        @DisplayName("ja OFF: nao consulta a fila")
        void alreadyOff() {
            professional.setWorkStatus(ProfessionalStatus.OFF);
            loadAttendanceAndProfessional();

            professionalService.finishAttendance(professionalId, attendanceId);

            verify(attendanceRepository, never()).existsByProfessional_IdAndStatusIn(any(), any());
        }
    }

    @Nested
    @DisplayName("servicos — guardas")
    class ServiceItemGuards {

        private ServiceItemRequest request;

        @BeforeEach
        void request() {
            request = mock(ServiceItemRequest.class);
            lenient().when(request.customName()).thenReturn("Corte");
        }

        private ServiceItem existing(String name) {
            ServiceItem si = new ServiceItem();
            si.setId(UUID.randomUUID());
            si.setCustomName(name);
            return si;
        }

        @Test
        @DisplayName("createServiceItem: profissional fora da lista da loja -> EstablishmentAccessDeniedException")
        void createServiceItem_notInEstablishment() {
            establishment.setProfessionals(new ArrayList<>());
            when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));

            assertThatThrownBy(() -> professionalService.createServiceItem(professionalId, request))
                    .isInstanceOf(EstablishmentAccessDeniedException.class);
            verifyNoInteractions(serviceItemService);
        }

        @Test
        @DisplayName("na loja: nem dono nem afiliado -> EstablishmentAccessDeniedException")
        void forEstablishment_strangerDenied() {
            professional.setEstablishment(null);
            when(establishmentRepository.findById(establishmentId)).thenReturn(Optional.of(establishment));
            when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));

            assertThatThrownBy(() -> professionalService.createServiceItemForEstablishment(
                    professionalId, establishmentId, request))
                    .isInstanceOf(EstablishmentAccessDeniedException.class);
        }

        @Test
        @DisplayName("na loja: afiliado de outra loja -> EstablishmentAccessDeniedException")
        void forEstablishment_affiliatedElsewhere() {
            Establishment other = new Establishment();
            other.setId(UUID.randomUUID());
            professional.setEstablishment(other);
            when(establishmentRepository.findById(establishmentId)).thenReturn(Optional.of(establishment));
            when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));

            assertThatThrownBy(() -> professionalService.createServiceItemForEstablishment(
                    professionalId, establishmentId, request))
                    .isInstanceOf(EstablishmentAccessDeniedException.class);
        }

        @Test
        @DisplayName("na loja: nome repetido (sem diferenciar caixa) -> ServiceItemAlreadyExistsException")
        void forEstablishment_duplicateName() {
            when(establishmentRepository.findById(establishmentId)).thenReturn(Optional.of(establishment));
            when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));
            when(establishmentService.getServicesItemByEstablishment(establishmentId))
                    .thenReturn(List.of(existing("Barba"), existing("CORTE")));

            assertThatThrownBy(() -> professionalService.createServiceItemForEstablishment(
                    professionalId, establishmentId, request))
                    .isInstanceOf(ServiceItemAlreadyExistsException.class);
            verifyNoInteractions(serviceItemService);
        }
    }
}
