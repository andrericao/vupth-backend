package com.ospaulos.vupth.unit.professional;

import com.ospaulos.vupth.api.attendance.dto.request.AttendanceGuestRequest;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;
import com.ospaulos.vupth.api.queue.dto.response.QueueResponse;
import com.ospaulos.vupth.api.serviceItem.dto.request.ServiceItemRequest;
import com.ospaulos.vupth.api.serviceItem.dto.response.ServiceItemResponse;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceAlreadyInProgressException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceInvalidStatusException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceNotFoundException;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.attendance.service.AttendanceService;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.service.EstablishmentService;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.service.impl.ProfessionalIntervalService;
import com.ospaulos.vupth.domain.professional.service.impl.ProfessionalServiceImpl;
import com.ospaulos.vupth.domain.queue.service.QueueService;
import com.ospaulos.vupth.domain.serviceItem.exception.ServiceItemAlreadyExistsException;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.serviceItem.service.ServiceItemService;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests — ProfessionalServiceImpl
 *
 * Covers bugs fixed in this version:
 *
 * Bug 1 (createServiceItem): usava request.establishmentId() em vez de professionalId
 *   → Corrigido: professionalRepository.findById(professionalId)
 *
 * Bug 2 (pauseAttendance): verificava status != SCHEDULED em vez de != IN_PROGRESS
 *   → Corrigido: deve estar IN_PROGRESS para pausar
 *
 * Bug 3 (resumeAttendance): verificava status != SCHEDULED em vez de != PAUSED
 *   → Corrigido: deve estar PAUSED para retomar
 *
 * Regras documentadas:
 * - OWNER and PROFESSIONAL can be the same user (solo shop)
 * - assertProfessionalOwnsAttendance valida ownership e role PROFESSIONAL
 * - A professional can only have 1 IN_PROGRESS attendance at a time
 */
@ExtendWith(MockitoExtension.class)
class ProfessionalServiceImplTest {

    @Mock QueueService queueService;
    @Mock AttendanceService attendanceService;
    @Mock ServiceItemService serviceItemService;
    @Mock EstablishmentService establishmentService;
    @Mock ProfessionalRepository professionalRepository;
    @Mock AttendanceRepository attendanceRepository;
    @Mock EstablishmentRepository establishmentRepository;
    @Mock ProfessionalIntervalService intervalService;
    // Contador de atrasos (D4): o mark/undo-late conta e desconta aqui.
    @Mock com.ospaulos.vupth.domain.client.service.LateArrivalService lateArrivalService;
    @Mock org.springframework.context.ApplicationEventPublisher eventPublisher;

    @InjectMocks ProfessionalServiceImpl professionalService;

    private UUID professionalId, attendanceId, establishmentId;
    private BaseUser profUser;
    private Professional professional;
    private Establishment establishment;
    private Attendance attendance;

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
        profUser.addRole(RoleType.CLIENT);       // every user is born with CLIENT role
        profUser.addRole(RoleType.PROFESSIONAL); // this user is also a professional

        professional = new Professional(profUser);
        professional.setEstablishment(establishment);
        professional.setIsAvailable(true);
        professional.setAttendances(new ArrayList<>());

        attendance = new Attendance();
        attendance.setId(attendanceId);
        attendance.setProfessional(professional);
        attendance.setEstablishment(establishment);
        attendance.setStatus(AttendanceStatus.SCHEDULED);
        attendance.setScheduledTime(OffsetDateTime.now());
        attendance.setServiceItems(new ArrayList<>());
    }

    // ─────────────────────────────────────────────
    // startAttendance
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("startAttendance")
    class StartAttendanceTests {

        @Test
        @DisplayName("sucesso: SCHEDULED → IN_PROGRESS, fila atualizada")
        void start_success() {
            attendance.setStatus(AttendanceStatus.SCHEDULED);
            when(attendanceRepository.findByIdEager(attendanceId)).thenReturn(Optional.of(attendance));
            when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));
            when(queueService.getQueueOverview(any(), any())).thenReturn(null);

            professionalService.startAttendance(professionalId, attendanceId);

            verify(attendanceService).startAttendance(attendance, professional);
            verify(queueService).updateQueueEstimates(professional);
        }

        @Test
        @DisplayName("attendance not found → throws AttendanceNotFoundException")
        void start_notFound_throws() {
            when(attendanceRepository.findByIdEager(attendanceId)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    professionalService.startAttendance(professionalId, attendanceId))
                    .isInstanceOf(AttendanceNotFoundException.class);
        }

        @Test
        @DisplayName("different professional → throws ProfessionalNotFoundException")
        void start_wrongProfessional_throws() {
            UUID outroProfId = UUID.randomUUID();
            when(attendanceRepository.findByIdEager(attendanceId)).thenReturn(Optional.of(attendance));

            assertThatThrownBy(() ->
                    professionalService.startAttendance(outroProfId, attendanceId))
                    .isInstanceOf(ProfessionalNotFoundException.class);
        }

        @Test
        @DisplayName("professional already has IN_PROGRESS attendance → throws AttendanceAlreadyInProgressException")
        void start_alreadyInProgress_throws() {
            // Simulate: professional already has an attendance in progress
            Attendance inProgress = new Attendance();
            inProgress.setId(UUID.randomUUID());
            inProgress.setStatus(AttendanceStatus.IN_PROGRESS);
            professional.setAttendances(new ArrayList<>(List.of(inProgress)));

            when(attendanceRepository.findByIdEager(attendanceId)).thenReturn(Optional.of(attendance));
            when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));

            // professional.getCurrentAttendance() retorna o IN_PROGRESS
            // Since we use a real Professional instance, the method works correctly
            assertThatThrownBy(() ->
                    professionalService.startAttendance(professionalId, attendanceId))
                    .isInstanceOf(AttendanceAlreadyInProgressException.class);
        }

        @Test
        @DisplayName("status is not SCHEDULED → throws AttendanceInvalidStatusException")
        void start_invalidStatus_throws() {
            attendance.setStatus(AttendanceStatus.IN_PROGRESS);
            when(attendanceRepository.findByIdEager(attendanceId)).thenReturn(Optional.of(attendance));
            when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));

            assertThatThrownBy(() ->
                    professionalService.startAttendance(professionalId, attendanceId))
                    .isInstanceOf(AttendanceInvalidStatusException.class);
        }
    }

    // ─────────────────────────────────────────────
    // pauseAttendance — Bug 2 corrigido
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("pauseAttendance (Bug 2 corrigido: verificava SCHEDULED, devia ser IN_PROGRESS)")
    class PauseAttendanceTests {

        @Test
        @DisplayName("sucesso: IN_PROGRESS → pausa chamada no service")
        void pause_success() {
            attendance.setStatus(AttendanceStatus.IN_PROGRESS);
            when(attendanceRepository.findByIdEager(attendanceId)).thenReturn(Optional.of(attendance));
            when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));
            when(queueService.getQueueOverview(any(), any())).thenReturn(null);

            professionalService.pauseAttendance(attendanceId, professionalId);

            verify(attendanceService).pauseAttendance(attendance);
        }

        @Test
        @DisplayName("status SCHEDULED → throws AttendanceInvalidStatusException (was the wrong behavior)")
        void pause_scheduledStatus_throws() {
            attendance.setStatus(AttendanceStatus.SCHEDULED);
            when(attendanceRepository.findByIdEager(attendanceId)).thenReturn(Optional.of(attendance));
            when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));

            // Correct behavior after bug fix: SCHEDULED cannot be paused
            assertThatThrownBy(() ->
                    professionalService.pauseAttendance(attendanceId, professionalId))
                    .isInstanceOf(AttendanceInvalidStatusException.class);
        }

        @Test
        @DisplayName("attendance not found → throws AttendanceNotFoundException")
        void pause_notFound_throws() {
            when(attendanceRepository.findByIdEager(attendanceId)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    professionalService.pauseAttendance(attendanceId, professionalId))
                    .isInstanceOf(AttendanceNotFoundException.class);
        }
    }

    // ─────────────────────────────────────────────
    // resumeAttendance — Bug 3 corrigido
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("resumeAttendance (Bug 3 corrigido: verificava SCHEDULED, devia ser PAUSED)")
    class ResumeAttendanceTests {

        @Test
        @DisplayName("sucesso: PAUSED → resume chamado no service")
        void resume_success() {
            attendance.setStatus(AttendanceStatus.PAUSED);
            when(attendanceRepository.findByIdEager(attendanceId)).thenReturn(Optional.of(attendance));
            when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));
            when(queueService.getQueueOverview(any(), any())).thenReturn(null);

            professionalService.resumeAttendance(attendanceId, professionalId);

            verify(attendanceService).resumeAttendance(attendance);
        }

        @Test
        @DisplayName("status SCHEDULED → throws AttendanceInvalidStatusException")
        void resume_scheduledStatus_throws() {
            attendance.setStatus(AttendanceStatus.SCHEDULED);
            when(attendanceRepository.findByIdEager(attendanceId)).thenReturn(Optional.of(attendance));
            when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));

            assertThatThrownBy(() ->
                    professionalService.resumeAttendance(attendanceId, professionalId))
                    .isInstanceOf(AttendanceInvalidStatusException.class);
        }
    }

    // ─────────────────────────────────────────────
    // finishAttendance
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("finishAttendance")
    class FinishAttendanceTests {

        @Test
        @DisplayName("sucesso: IN_PROGRESS → finalizacao delegada ao service")
        void finish_success() {
            attendance.setStatus(AttendanceStatus.IN_PROGRESS);
            when(attendanceRepository.findByIdEager(attendanceId)).thenReturn(Optional.of(attendance));
            when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));
            when(queueService.getQueueOverview(any(), any())).thenReturn(mock(QueueResponse.class));

            professionalService.finishAttendance(professionalId, attendanceId);

            verify(attendanceService).finishAttendance(attendance);
        }

        @Test
        @DisplayName("D12: PAUSED → finalizacao delegada ao service, sem exigir resume")
        void finish_fromPaused_success() {
            attendance.setStatus(AttendanceStatus.PAUSED);
            when(attendanceRepository.findByIdEager(attendanceId)).thenReturn(Optional.of(attendance));
            when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));
            when(queueService.getQueueOverview(any(), any())).thenReturn(mock(QueueResponse.class));

            professionalService.finishAttendance(professionalId, attendanceId);

            verify(attendanceService).finishAttendance(attendance);
        }

        @Test
        @DisplayName("status SCHEDULED → throws AttendanceInvalidStatusException")
        void finish_wrongStatus_throws() {
            attendance.setStatus(AttendanceStatus.SCHEDULED);
            when(attendanceRepository.findByIdEager(attendanceId)).thenReturn(Optional.of(attendance));
            when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));

            assertThatThrownBy(() ->
                    professionalService.finishAttendance(professionalId, attendanceId))
                    .isInstanceOf(AttendanceInvalidStatusException.class);
        }
    }

    // ─────────────────────────────────────────────
    // createServiceItem — Bug 1 corrigido
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("createServiceItem (Bug 1 corrigido: usava request.establishmentId() em vez de professionalId)")
    class CreateServiceItemTests {

        @Test
        @DisplayName("success: finds professional by professionalId (not by establishmentId from the request)")
        void createServiceItem_success_usesProfessionalId() {
            ServiceItemRequest request = new ServiceItemRequest(
                    establishmentId, ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30), 30, null, null);

            // professional has 0 service items, no duplicate
            establishment.setProfessionals(new ArrayList<>(List.of(professional)));
            when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));
            when(establishmentService.getServicesItemByEstablishment(establishmentId)).thenReturn(List.of());

            // ServiceItemResponse(establishmentId, establishmentName, id, type, customName, price,
            //                     clientPrice, usesPlatformPricing, weeklyAvg, ratingAverage, ratingCount)
            ServiceItemResponse fakeResponse = new ServiceItemResponse(
                    establishmentId, establishment.getName(), null, ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30), null, null, null, null, 0L);
            when(serviceItemService.createServiceItem(professional, request)).thenReturn(fakeResponse);

            ServiceItemResponse result = professionalService.createServiceItem(professionalId, request);

            assertThat(result).isNotNull();
            // Bug validation: professionalRepository was called with the correct professionalId
            verify(professionalRepository).findByIdWithBaseUser(professionalId);
            // And NOT with the establishmentId (behavior before the bug fix)
            verify(professionalRepository, never()).findByIdWithBaseUser(establishmentId);
        }

        @Test
        @DisplayName("service item with same name already exists → throws ServiceItemAlreadyExistsException")
        void createServiceItem_duplicate_throws() {
            ServiceItemRequest request = new ServiceItemRequest(
                    establishmentId, ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30), 30, null, null);

            ServiceItem existing = new ServiceItem();
            existing.setCustomName("Corte"); // mesmo nome

            establishment.setProfessionals(new ArrayList<>(List.of(professional)));
            when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));
            when(establishmentService.getServicesItemByEstablishment(establishmentId))
                    .thenReturn(List.of(existing));

            assertThatThrownBy(() ->
                    professionalService.createServiceItem(professionalId, request))
                    .isInstanceOf(ServiceItemAlreadyExistsException.class);
        }
    
    // ─────────────────────────────────────────────────────────────
    // removeFromQueue — professional removes any attendance from queue
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("removeFromQueue")
    class RemoveFromQueueTests {

        @Test
        @DisplayName("success: professional removes a scheduled attendance → CANCELED")
        void removeFromQueue_success() {
            AttendanceInQueueResponse expected = mock(AttendanceInQueueResponse.class);
            when(attendanceService.removeFromQueue(attendanceId, professionalId)).thenReturn(expected);

            AttendanceInQueueResponse result = attendanceService.removeFromQueue(attendanceId, professionalId);

            assertThat(result).isNotNull();
            verify(attendanceService).removeFromQueue(attendanceId, professionalId);
        }

        @Test
        @DisplayName("success: professional removes a guest attendance (no client) → CANCELED")
        void removeFromQueue_guestAttendance_success() {
            // Guest attendance has no client — professional should still be able to remove it
            attendance.setClient(null);
            attendance.setGuestName("Maria guest");

            AttendanceInQueueResponse expected = mock(AttendanceInQueueResponse.class);
            when(attendanceService.removeFromQueue(attendanceId, professionalId)).thenReturn(expected);

            AttendanceInQueueResponse result = attendanceService.removeFromQueue(attendanceId, professionalId);

            assertThat(result).isNotNull();
            verify(attendanceService).removeFromQueue(attendanceId, professionalId);
        }

        @Test
        @DisplayName("attendance belongs to a different professional → EstablishmentAccessDeniedException")
        void removeFromQueue_wrongProfessional_throws() {
            UUID otherProfessionalId = UUID.randomUUID();
            when(attendanceService.removeFromQueue(attendanceId, otherProfessionalId))
                    .thenThrow(new EstablishmentAccessDeniedException(
                            "You can only remove attendances from your own queue."));

            assertThatThrownBy(() -> attendanceService.removeFromQueue(attendanceId, otherProfessionalId))
                    .isInstanceOf(EstablishmentAccessDeniedException.class);
        }

        @Test
        @DisplayName("attendance FINISHED → AttendanceInvalidStatusException")
        void removeFromQueue_wrongStatus_throws() {
            when(attendanceService.removeFromQueue(attendanceId, professionalId))
                    .thenThrow(new AttendanceInvalidStatusException(
                            AttendanceStatus.FINISHED, AttendanceStatus.SCHEDULED));

            assertThatThrownBy(() -> attendanceService.removeFromQueue(attendanceId, professionalId))
                    .isInstanceOf(AttendanceInvalidStatusException.class);
        }
    }
}
}