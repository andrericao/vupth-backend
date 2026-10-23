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
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotOpenException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotAffiliatedException;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;
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
 * - Sucesso: status PAUSED → FINISHED, a pausa aberta nao conta como servico (D12)
 * - Invalid status (not IN_PROGRESS/PAUSED) → AttendanceInvalidStatusException
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
    @Mock org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock ServiceItemRepository serviceItemRepository;
    @Mock ProfessionalRepository professionalRepository;
    @Mock ClientRepository clientRepository;
    // Espera que o cliente le (multa de saida D4): vazio = fora da fila de espera -> sem multa.
    @Mock com.ospaulos.vupth.domain.queue.service.ClientWaitCalculator clientWaitCalculator;
    // Cobranca da multa no proximo atendimento (D4).
    @Mock com.ospaulos.vupth.domain.client.service.ClientPenaltyService clientPenaltyService;

    @Mock com.ospaulos.vupth.domain.client.service.FirstAttendanceExemption firstAttendanceExemption;
    @Mock com.ospaulos.vupth.domain.testrun.service.TestRunService testRunService;
    @Mock com.ospaulos.vupth.domain.campaign.service.CouponService couponService;
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
        // Aberta agora (sem janela de horario => so o openStatus manda). Necessario pro gate de
        // entrada do walk-in (BUG5 parte C1): so loja "aberta agora" recebe entrada sem targetTime.
        establishment.setOpenStatus(OpenStatus.OPEN);

        profUser = new BaseUser();
        profUser.setId(professionalId);
        profUser.setFirstName("Carlos");

        professional = new Professional(profUser);
        professional.setEstablishment(establishment);
        professional.setIsAvailable(true);
        // 20/09: quem nao esta em expediente nao recebe fila. O profissional nasce OFF; aqui ele
        // chega para trabalhar, porque o assunto destes casos e outro (dono do establishment,
        // servico inexistente, duplo ingresso).
        professional.setWorkStatus(ProfessionalStatus.WORKING);
        professional.setAttendances(new ArrayList<>());

        clientUser = new BaseUser();
        clientUser.setId(clientId);
        clientUser.setFirstName("John");
        client = new Client(clientUser);

        ServiceItem si = new ServiceItem();
        si.setId(UUID.randomUUID());
        si.setCustomName("Corte");
        si.setPrice(BigDecimal.valueOf(30));
        si.setEstimatedMinutes(30);
        si.setProfessional(professional); // invariante: item pertence ao profissional (commit 3286667)
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

        @Test
        @DisplayName("throws EstablishmentNotOpenException: walk-in (sem targetTime) e loja fechada — gate BUG5 C1")
        void createAttendance_walkIn_establishmentClosed() {
            // request do setUp usa o construtor de 8 args => targetTime == null (walk-in imediato).
            // Com a loja CLOSED, isOpenNow() == false, entao o gate de entrada barra antes de tocar
            // client/serviceItems.
            establishment.setOpenStatus(OpenStatus.CLOSED);
            when(professionalRepository.findById(professionalId)).thenReturn(Optional.of(professional));

            assertThatThrownBy(() -> attendanceService.createAttendance(request))
                    .isInstanceOf(EstablishmentNotOpenException.class);
            verify(attendanceRepository, never()).save(any());
            verify(clientRepository, never()).findById(any());
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
        @DisplayName("D12: PAUSED → FINISHED direto, e a pausa aberta sai da duracao")
        void finish_fromPaused_closesOpenPause() {
            attendance.setStatus(AttendanceStatus.PAUSED);
            attendance.setIsTimerActive(false);
            attendance.setStartTime(OffsetDateTime.now().minusMinutes(30));
            attendance.setLastPauseTime(OffsetDateTime.now().minusMinutes(10));
            attendance.setPausedDurationInMinutes(0);
            when(attendanceRepository.save(any())).thenReturn(attendance);

            attendanceService.finishAttendance(attendance);

            assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.FINISHED);
            assertThat(attendance.getEndTime()).isNotNull();
            assertThat(attendance.getIsTimerActive()).isFalse();
            assertThat(attendance.getLastPauseTime()).isNull();
            // 30min desde o inicio, 10 deles parado: servico = 20 (±1 pelo truncamento de minuto)
            assertThat(attendance.getPausedDurationInMinutes()).isBetween(9, 10);
            assertThat(attendance.getDurationInMinutes()).isBetween(19, 21);
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

    // ─────────────────────────────────────────────
    // Respostas com a pausa EM CURSO (A4, metade backend)
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("respostas de atendimento pausado")
    class PausedResponsesTests {

        @BeforeEach
        void pausedTwentyMinutesIn() {
            OffsetDateTime now = OffsetDateTime.now();
            attendance.setStatus(AttendanceStatus.PAUSED);
            attendance.setIsTimerActive(false);
            attendance.setEstimatedMinutes(40);
            attendance.setPausedDurationInMinutes(0);
            attendance.setStartTime(now.minusMinutes(30));
            attendance.setLastPauseTime(now.minusMinutes(10));
        }

        @Test
        @DisplayName("in-progress: servico parado na pausa, pausa aberta contada, restante congelado")
        void inProgressResponse_countsOpenPause() {
            var response = attendanceService.buildAttendanceInProgressResponse(attendance);

            assertThat(response.isPaused()).isTrue();
            assertThat(response.elapsedMinutes()).isEqualTo(20);
            assertThat(response.pausedTimeMinutes()).isEqualTo(10);
            assertThat(response.totalElapsedMinutes()).isEqualTo(30);
            assertThat(response.remainingMinutes()).isEqualTo(20);
        }

        @Test
        @DisplayName("paused: mesmos numeros da resposta in-progress")
        void pausedResponse_countsOpenPause() {
            var response = attendanceService.buildPausedAttendance(attendance);

            assertThat(response.elapsedMinutes()).isEqualTo(20);
            assertThat(response.pausedDurationInMinutes()).isEqualTo(10);
            assertThat(response.remainingMinutes()).isEqualTo(20);
        }
    }
}
