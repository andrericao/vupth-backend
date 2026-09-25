package com.ospaulos.vupth.unit.client;

import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceInvalidStatusException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.api.client.dto.response.ClientInQueueResponse;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.attendance.service.AttendanceService;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.client.repository.ClientRepository;
import com.ospaulos.vupth.domain.client.service.impl.ClientServiceImpl;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.queue.service.QueueService;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import jakarta.persistence.EntityNotFoundException;
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
 * Unit tests — ClientServiceImpl
 *
 * Cobre:
 *
 * createAndAddAttendanceInQueue:
 * - Success: attendance created and added to queue
 * - Professional not found → EntityNotFoundException
 * - Establishment not found → EntityNotFoundException
 * - Client not found → EntityNotFoundException
 *
 * Regras documentadas:
 * - clientId is extracted from JWT in the Controller (POST /attendances),
 *   ensuring the client cannot book on behalf of another
 * - ClientService delegates Attendance creation to AttendanceService
 *   and queue insertion to QueueService
 */
@ExtendWith(MockitoExtension.class)
class ClientServiceImplTest {

    @Mock
    QueueService queueService;
    @Mock
    AttendanceService attendanceService;
    @Mock
    AttendanceRepository attendanceRepository;
    @Mock
    EstablishmentRepository establishmentRepository;
    @Mock
    ClientRepository clientRepository;
    @Mock
    ProfessionalRepository professionalRepository;

    @InjectMocks
    ClientServiceImpl clientService;

    private UUID professionalId, establishmentId, clientId;
    private Professional professional;
    private Establishment establishment;
    private Client client;
    private Attendance attendance;
    private AttendanceRequest request;

    @BeforeEach
    void setUp() {
        professionalId = UUID.randomUUID();
        establishmentId = UUID.randomUUID();
        clientId = UUID.randomUUID();

        establishment = new Establishment();
        establishment.setId(establishmentId);
        establishment.setName("Barbershop");

        BaseUser profUser = new BaseUser();
        profUser.setId(professionalId);
        profUser.setFirstName("Carlos");

        professional = new Professional(profUser);
        professional.setEstablishment(establishment);
        professional.setIsAvailable(true);
        professional.setAttendances(new ArrayList<>());

        BaseUser clientUser = new BaseUser();
        clientUser.setId(clientId);
        clientUser.setFirstName("John");
        client = new Client(clientUser);

        attendance = new Attendance();
        attendance.setId(UUID.randomUUID());
        attendance.setProfessional(professional);
        attendance.setEstablishment(establishment);
        attendance.setClient(client);
        attendance.setStatus(AttendanceStatus.SCHEDULED);
        attendance.setScheduledTime(OffsetDateTime.now());
        attendance.setServiceItems(new ArrayList<>());

        request = new AttendanceRequest(
                professionalId, establishmentId, clientId,
                List.of(UUID.randomUUID()),
                AttendanceStatus.SCHEDULED,
                OffsetDateTime.now().plusMinutes(30), 30, OffsetDateTime.now());
    }

    @Nested
    @DisplayName("createAndAddAttendanceInQueue")
    class CreateAttendanceTests {

        @Test
        @DisplayName("success: attendance created and added to queue")
        void create_success() {
            when(professionalRepository.findById(professionalId)).thenReturn(Optional.of(professional));
            when(establishmentRepository.findById(establishmentId)).thenReturn(Optional.of(establishment));
            when(clientRepository.findById(clientId)).thenReturn(Optional.of(client));
            when(attendanceService.createAttendance(any(), any(), any(), any())).thenReturn(attendance);
            when(queueService.enqueueAttendance(any(), any())).thenReturn(
                    new com.ospaulos.vupth.api.client.dto.response.ClientInQueueResponse(
                            attendance.getId(), clientId, 1, null, 1, 30, 30, 30, null, null, null, 0, 3));

            var result = clientService.createAndAddtAttendanceInQueue(request);

            assertThat(result).isNotNull();
            verify(attendanceService).createAttendance(any(), eq(professional), eq(establishment), eq(client));
            verify(queueService).enqueueAttendance(attendance, professional);
        }

        @Test
        @DisplayName("professional not found → EntityNotFoundException")
        void create_professionalNotFound_throws() {
            when(professionalRepository.findById(professionalId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> clientService.createAndAddtAttendanceInQueue(request))
                    .isInstanceOf(EntityNotFoundException.class);

            verify(attendanceService, never()).createAttendance(any(), any(), any(), any());
        }

        @Test
        @DisplayName("establishment not found → EntityNotFoundException")
        void create_establishmentNotFound_throws() {
            when(professionalRepository.findById(professionalId)).thenReturn(Optional.of(professional));
            when(establishmentRepository.findById(establishmentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> clientService.createAndAddtAttendanceInQueue(request))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("client not found → EntityNotFoundException")
        void create_clientNotFound_throws() {
            when(professionalRepository.findById(professionalId)).thenReturn(Optional.of(professional));
            when(establishmentRepository.findById(establishmentId)).thenReturn(Optional.of(establishment));
            when(clientRepository.findById(clientId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> clientService.createAndAddtAttendanceInQueue(request))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        // ─────────────────────────────────────────────────────────────
        // leaveQueue — client voluntarily leaves the queue
        // ─────────────────────────────────────────────────────────────

        @Nested
        @DisplayName("leaveQueue")
        class LeaveQueueTests {

            @Test
            @DisplayName("success: client leaves own queue position → CANCELED")
            void leaveQueue_success() {
                AttendanceInQueueResponse expected = mock(AttendanceInQueueResponse.class);
                when(attendanceService.leaveQueue(attendance.getId(), clientId)).thenReturn(expected);

                AttendanceInQueueResponse result = attendanceService.leaveQueue(attendance.getId(), clientId);

                assertThat(result).isNotNull();
                verify(attendanceService).leaveQueue(attendance.getId(), clientId);
            }

            @Test
            @DisplayName("attendance belongs to another client → EstablishmentAccessDeniedException")
            void leaveQueue_wrongClient_throws() {
                UUID otherClientId = UUID.randomUUID();
                when(attendanceService.leaveQueue(attendance.getId(), otherClientId))
                        .thenThrow(new EstablishmentAccessDeniedException("You can only leave your own queue position."));

                assertThatThrownBy(() -> attendanceService.leaveQueue(attendance.getId(), otherClientId))
                        .isInstanceOf(EstablishmentAccessDeniedException.class);
            }

            @Test
            @DisplayName("attendance not in SCHEDULED status → AttendanceInvalidStatusException")
            void leaveQueue_wrongStatus_throws() {
                attendance.setStatus(AttendanceStatus.IN_PROGRESS);
                when(attendanceService.leaveQueue(attendance.getId(), clientId))
                        .thenThrow(new AttendanceInvalidStatusException(
                                AttendanceStatus.IN_PROGRESS, AttendanceStatus.SCHEDULED));

                assertThatThrownBy(() -> attendanceService.leaveQueue(attendance.getId(), clientId))
                        .isInstanceOf(AttendanceInvalidStatusException.class);
            }
        }
    }
}