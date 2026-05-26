package com.ospaulos.vupth.unit.attendance;

import com.ospaulos.vupth.api.attendance.dto.request.AttendanceGuestRequest;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceInvalidStatusException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceNotFoundException;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.attendance.service.impl.AttendanceServiceImpl;
import com.ospaulos.vupth.domain.client.exception.ClientNotFoundException;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.client.repository.ClientRepository;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotAffiliatedException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.serviceItem.exception.ServiceItemNotFoundException;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
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
 * Unit tests — AttendanceServiceImpl
 *
 * Cobre:
 *
 * createAttendance(request):
 * - Sucesso: UUID comparison via .equals() (bug corrigido)
 * - Professional not found → ProfessionalNotFoundException
 * - Establishment mismatch → EstablishmentNotFoundException
 * - Client not found → ClientNotFoundException
 * - ServiceItems vazio → ServiceItemNotFoundException
 *
 * createGuestAttendance:
 * - With service items
 * - Without service items (MVP: optional)
 * - Without name (anonymous guest)
 *
 * startAttendance:
 * - Sucesso: status SCHEDULED → IN_PROGRESS
 * - Invalid status (not SCHEDULED) → AttendanceInvalidStatusException
 *
 * finishAttendance:
 * - Sucesso: status IN_PROGRESS → FINISHED
 * - Invalid status (not IN_PROGRESS) → AttendanceInvalidStatusException
 * - Sem startTime → AttendanceInvalidStatusException
 *
 * cancelAttendance(UUID, UUID):
 * - Sucesso: status → CANCELED
 * - Not found → AttendanceNotFoundException
 *
 * Regras documentadas:
 * - Guest: client is null, guestName can be null (anonymous)
 * - serviceItemIds for guest are optional in the MVP
 * - UUID deve ser comparado com .equals(), nunca com == (bug original corrigido)
 * - Logs sempre usam clientName com fallback para guestName e depois "anonymous"
 */
@ExtendWith(MockitoExtension.class)
class AttendanceServiceImplTest {

    @Mock AttendanceRepository attendanceRepository;
    @Mock ServiceItemRepository serviceItemRepository;
    @Mock ProfessionalRepository professionalRepository;
    @Mock ClientRepository clientRepository;

    @InjectMocks AttendanceServiceImpl attendanceService;

    // shared test data
    private UUID professionalId, clientId, establishmentId, attendanceId;
    private Professional professional;
    private Establishment establishment;
    private Client client;
    private BaseUser profUser, clientUser;
    private List<ServiceItem> serviceItems;
    private Attendance attendance;

    @BeforeEach
    void setUp() {
        professionalId = UUID.randomUUID();
        clientId = UUID.randomUUID();
        establishmentId = UUID.randomUUID();
        attendanceId = UUID.randomUUID();

        establishment = new Establishment();
        establishment.setId(establishmentId);
        establishment.setName("Barbershop Teste");

        profUser = new BaseUser();
        profUser.setId(professionalId);
        profUser.setFirstName("Carlos");

        professional = new Professional(profUser);
        professional.setEstablishment(establishment);
        professional.setIsAvailable(true);
        professional.setAttendances(new ArrayList<>());

        clientUser = new BaseUser();
        clientUser.setId(clientId);
        clientUser.setFirstName("John");
        client = new Client(clientUser);

        ServiceItem si = new ServiceItem();
        si.setCustomName("Corte");
        si.setPrice(BigDecimal.valueOf(30));
        si.setEstimatedMinutes(30);
        serviceItems = List.of(si);

        attendance = new Attendance();
        attendance.setId(attendanceId);
        attendance.setProfessional(professional);
        attendance.setEstablishment(establishment);
        attendance.setClient(client);
        attendance.setStatus(AttendanceStatus.SCHEDULED);
        attendance.setScheduledTime(OffsetDateTime.now());
        attendance.setServiceItems(serviceItems);
    }

    // ─────────────────────────────────────────────
    // createAttendance(request) — via repositories
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("createAttendance(AttendanceRequest)")
    class CreateAttendanceViaRequestTests {

        private AttendanceRequest request;

        @BeforeEach
        void setUpRequest() {
            request = new AttendanceRequest(
                    professionalId, establishmentId, clientId,
                    List.of(UUID.randomUUID()),
                    AttendanceStatus.SCHEDULED,
                    OffsetDateTime.now(), 30, OffsetDateTime.now());
        }

        @Test
        @DisplayName("sucesso: cria attendance com establishment.id.equals() — bug UUID corrigido")
        void createAttendance_success_usesEquals() {
            // Este teste valida o bug corrigido: == → .equals()
            when(professionalRepository.findById(professionalId)).thenReturn(Optional.of(professional));
            when(clientRepository.findById(clientId)).thenReturn(Optional.of(client));
            when(serviceItemRepository.findAllById(any())).thenReturn(serviceItems);
            when(attendanceRepository.save(any())).thenAnswer(inv -> {
                Attendance a = inv.getArgument(0);
                a.setId(attendanceId);
                return a;
            });

            AttendanceInQueueResponse result = attendanceService.createAttendance(request);

            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo(AttendanceStatus.SCHEDULED);
            assertThat(result.professionalId()).isEqualTo(professionalId);
            assertThat(result.clientId()).isEqualTo(clientId);
            verify(attendanceRepository).save(any());
        }

        @Test
        @DisplayName("throws ProfessionalNotFoundException if professional does not exist")
        void createAttendance_professionalNotFound() {
            when(professionalRepository.findById(professionalId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> attendanceService.createAttendance(request))
                    .isInstanceOf(ProfessionalNotFoundException.class);
            verify(attendanceRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws EstablishmentNotFoundException if establishment does not match professional")
        void createAttendance_establishmentMismatch() {
            // Establishment diferente do professional
            Establishment outro = new Establishment();
            outro.setId(UUID.randomUUID()); // ID diferente do request
            professional.setEstablishment(outro);

            when(professionalRepository.findById(professionalId)).thenReturn(Optional.of(professional));

            assertThatThrownBy(() -> attendanceService.createAttendance(request))
                    .isInstanceOf(EstablishmentNotFoundException.class);
        }

        @Test
        @DisplayName("throws ProfessionalNotAffiliatedException if professional has no establishment")
        void createAttendance_professionalNotAffiliated() {
            professional.setEstablishment(null);
            when(professionalRepository.findById(professionalId)).thenReturn(Optional.of(professional));

            assertThatThrownBy(() -> attendanceService.createAttendance(request))
                    .isInstanceOf(ProfessionalNotAffiliatedException.class);
            verify(attendanceRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws ClientNotFoundException if client does not exist")
        void createAttendance_clientNotFound() {
            when(professionalRepository.findById(professionalId)).thenReturn(Optional.of(professional));
            when(clientRepository.findById(clientId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> attendanceService.createAttendance(request))
                    .isInstanceOf(ClientNotFoundException.class);
        }

        @Test
        @DisplayName("throws ServiceItemNotFoundException if serviceItems is empty")
        void createAttendance_noServiceItems() {
            when(professionalRepository.findById(professionalId)).thenReturn(Optional.of(professional));
            when(clientRepository.findById(clientId)).thenReturn(Optional.of(client));
            when(serviceItemRepository.findAllById(any())).thenReturn(List.of());

            assertThatThrownBy(() -> attendanceService.createAttendance(request))
                    .isInstanceOf(ServiceItemNotFoundException.class);
        }
    }

    // ─────────────────────────────────────────────
    // createGuestAttendance
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("createGuestAttendance")
    class CreateGuestAttendanceTests {

        @Test
        @DisplayName("success: guest with name and service items")
        void createGuest_withNameAndServices() {
            AttendanceGuestRequest req = new AttendanceGuestRequest(
                    establishmentId, professionalId, List.of(UUID.randomUUID()), "John Guest");

            when(serviceItemRepository.findAllById(any())).thenReturn(serviceItems);
            when(attendanceRepository.save(any())).thenAnswer(inv -> {
                Attendance a = inv.getArgument(0);
                a.setId(UUID.randomUUID());
                return a;
            });

            Attendance result = attendanceService.createGuestAttendance(req, professional, establishment);

            assertThat(result.getClient()).isNull();          // guest has no client
            assertThat(result.getGuestName()).isEqualTo("John Guest");
            assertThat(result.getServiceItems()).isNotEmpty();
            assertThat(result.getStatus()).isEqualTo(AttendanceStatus.SCHEDULED);
        }

        @Test
        @DisplayName("success: anonymous guest without service items — MVP allows this")
        void createGuest_anonymous_noServices() {
            AttendanceGuestRequest req = new AttendanceGuestRequest(
                    establishmentId, professionalId, null, null);

            when(attendanceRepository.save(any())).thenAnswer(inv -> {
                Attendance a = inv.getArgument(0);
                a.setId(UUID.randomUUID());
                return a;
            });

            Attendance result = attendanceService.createGuestAttendance(req, professional, establishment);

            assertThat(result.getClient()).isNull();
            assertThat(result.getGuestName()).isNull();
            assertThat(result.getServiceItems()).isEmpty();
            // serviceItemRepository nunca deve ser chamado com lista nula
            verify(serviceItemRepository, never()).findAllById(any());
        }

        @Test
        @DisplayName("success: guest with name but without service items")
        void createGuest_namedWithoutServices() {
            AttendanceGuestRequest req = new AttendanceGuestRequest(
                    establishmentId, professionalId, List.of(), "Maria");

            when(attendanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Attendance result = attendanceService.createGuestAttendance(req, professional, establishment);

            assertThat(result.getGuestName()).isEqualTo("Maria");
            assertThat(result.getServiceItems()).isEmpty();
        }

        @Test
        @DisplayName("throws ProfessionalNotAffiliatedException if professional has no establishment")
        void createGuest_professionalNotAffiliated() {
            professional.setEstablishment(null);
            AttendanceGuestRequest req = new AttendanceGuestRequest(
                    establishmentId, professionalId, null, "Guest");

            assertThatThrownBy(() -> attendanceService.createGuestAttendance(req, professional, establishment))
                    .isInstanceOf(ProfessionalNotAffiliatedException.class);
            verify(attendanceRepository, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────
    // startAttendance
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("startAttendance")
    class StartAttendanceTests {

        @Test
        @DisplayName("sucesso: SCHEDULED → IN_PROGRESS, startTime e timer definidos")
        void start_success() {
            attendance.setStatus(AttendanceStatus.SCHEDULED);
            when(attendanceRepository.save(any())).thenReturn(attendance);

            attendanceService.startAttendance(attendance, professional);

            assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.IN_PROGRESS);
            assertThat(attendance.getStartTime()).isNotNull();
            assertThat(attendance.getIsTimerActive()).isTrue();
            verify(attendanceRepository).save(attendance);
        }

        @Test
        @DisplayName("status IN_PROGRESS → throws AttendanceInvalidStatusException")
        void start_alreadyInProgress_throws() {
            attendance.setStatus(AttendanceStatus.IN_PROGRESS);

            assertThatThrownBy(() -> attendanceService.startAttendance(attendance, professional))
                    .isInstanceOf(AttendanceInvalidStatusException.class);
            verify(attendanceRepository, never()).save(any());
        }

        @Test
        @DisplayName("status CANCELED → throws AttendanceInvalidStatusException")
        void start_canceled_throws() {
            attendance.setStatus(AttendanceStatus.CANCELLED);

            assertThatThrownBy(() -> attendanceService.startAttendance(attendance, professional))
                    .isInstanceOf(AttendanceInvalidStatusException.class);
        }

        @Test
        @DisplayName("guest (client=null): startAttendance does not throw NPE")
        void start_guestAttendance_noNPE() {
            attendance.setClient(null);
            attendance.setGuestName("Carlos Guest");
            attendance.setStatus(AttendanceStatus.SCHEDULED);
            when(attendanceRepository.save(any())).thenReturn(attendance);

            assertThatNoException().isThrownBy(() ->
                    attendanceService.startAttendance(attendance, professional));
        }
    }

    // ─────────────────────────────────────────────
    // finishAttendance
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("finishAttendance")
    class FinishAttendanceTests {

        @Test
        @DisplayName("sucesso: IN_PROGRESS → FINISHED, endTime definido")
        void finish_success() {
            attendance.setStatus(AttendanceStatus.IN_PROGRESS);
            attendance.setStartTime(OffsetDateTime.now().minusMinutes(30));
            when(attendanceRepository.save(any())).thenReturn(attendance);

            attendanceService.finishAttendance(attendance);

            assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.FINISHED);
            assertThat(attendance.getEndTime()).isNotNull();
            assertThat(attendance.getIsTimerActive()).isFalse();
        }

        @Test
        @DisplayName("status SCHEDULED → throws AttendanceInvalidStatusException")
        void finish_notInProgress_throws() {
            attendance.setStatus(AttendanceStatus.SCHEDULED);

            assertThatThrownBy(() -> attendanceService.finishAttendance(attendance))
                    .isInstanceOf(AttendanceInvalidStatusException.class);
        }

        @Test
        @DisplayName("no startTime → throws AttendanceInvalidStatusException")
        void finish_noStartTime_throws() {
            attendance.setStatus(AttendanceStatus.IN_PROGRESS);
            attendance.setStartTime(null);

            assertThatThrownBy(() -> attendanceService.finishAttendance(attendance))
                    .isInstanceOf(AttendanceInvalidStatusException.class);
        }

        @Test
        @DisplayName("guest (client=null): finishAttendance does not throw NPE")
        void finish_guestAttendance_noNPE() {
            attendance.setClient(null);
            attendance.setGuestName("Anonymous");
            attendance.setStatus(AttendanceStatus.IN_PROGRESS);
            attendance.setStartTime(OffsetDateTime.now().minusMinutes(20));
            when(attendanceRepository.save(any())).thenReturn(attendance);

            assertThatNoException().isThrownBy(() ->
                    attendanceService.finishAttendance(attendance));
        }
    }

    // ─────────────────────────────────────────────
    // cancelAttendance(UUID, UUID)
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("cancelAttendance(UUID, UUID)")
    class CancelAttendanceTests {

        @Test
        @DisplayName("sucesso: status → CANCELED")
        void cancel_success() {
            attendance.setStatus(AttendanceStatus.SCHEDULED);
            when(attendanceRepository.findByIdEager(attendanceId)).thenReturn(Optional.of(attendance));
            when(attendanceRepository.save(any())).thenReturn(attendance);

            AttendanceInQueueResponse result =
                    attendanceService.cancelAttendance(attendanceId, clientId);

            assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.CANCELLED);
            verify(attendanceRepository).save(attendance);
        }

        @Test
        @DisplayName("attendance not found → throws AttendanceNotFoundException")
        void cancel_notFound_throws() {
            UUID inexistente = UUID.randomUUID();
            when(attendanceRepository.findByIdEager(inexistente)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    attendanceService.cancelAttendance(inexistente, clientId))
                    .isInstanceOf(AttendanceNotFoundException.class);
        }
    }

    // ─────────────────────────────────────────────
    // createAttendance(request, professional, establishment, client) — 4-arg overload
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("createAttendance(request, professional, establishment, client)")
    class CreateAttendanceFourArgTests {

        private AttendanceRequest request;

        @BeforeEach
        void setUpRequest() {
            request = new AttendanceRequest(
                    professionalId, establishmentId, clientId,
                    List.of(UUID.randomUUID()),
                    AttendanceStatus.SCHEDULED,
                    OffsetDateTime.now(), 30, OffsetDateTime.now());
        }

        @Test
        @DisplayName("throws ProfessionalNotAffiliatedException if professional has no establishment")
        void createAttendance_professionalNotAffiliated() {
            professional.setEstablishment(null);

            assertThatThrownBy(() ->
                    attendanceService.createAttendance(request, professional, establishment, client))
                    .isInstanceOf(ProfessionalNotAffiliatedException.class);
            verify(attendanceRepository, never()).save(any());
        }
    }
}
