package com.ospaulos.vupth.unit.attendance;

import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInProgressResponse;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceAlreadyCancelledException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceCancelNotAllowedException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceInvalidStatusException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceNotFoundException;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRatingRepository;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.attendance.service.impl.AttendanceServiceImpl;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository;
import com.ospaulos.vupth.domain.client.repository.ClientRepository;
import com.ospaulos.vupth.domain.client.service.ClientPenaltyService;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentUnavailableException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.ShowcasePage;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotAffiliatedException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;
import com.ospaulos.vupth.domain.queue.service.ClientWaitCalculator;
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
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AttendanceServiceImpl — os caminhos que o {@link AttendanceServiceImplTest} e os testes de
 * integracao nao passavam (cobertura-98): o create de 4 argumentos ate o fim, as guardas do cancel e
 * da intencao, a fila do proximo, a media semanal, os builders e a listagem paginada.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceServiceImplCoverageTest {

    @Mock AttendanceRepository attendanceRepository;
    @Mock ServiceItemRepository serviceItemRepository;
    @Mock ProfessionalRepository professionalRepository;
    @Mock ClientRepository clientRepository;
    @Mock EstablishmentRepository establishmentRepository;
    @Mock ClientPenaltyEventRepository penaltyEventRepository;
    @Mock AttendanceRatingRepository attendanceRatingRepository;
    @Mock ClientWaitCalculator clientWaitCalculator;
    @Mock ClientPenaltyService clientPenaltyService;

    @InjectMocks AttendanceServiceImpl attendanceService;

    private UUID professionalId, clientId, establishmentId, attendanceId;
    private Professional professional;
    private Establishment establishment;
    private Client client;
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
        establishment.setOpenStatus(OpenStatus.OPEN);

        BaseUser profUser = new BaseUser();
        profUser.setId(professionalId);
        profUser.setFirstName("Carlos");
        professional = new Professional(profUser);
        professional.setEstablishment(establishment);
        professional.setIsAvailable(true);
        professional.setWorkStatus(ProfessionalStatus.WORKING);
        professional.setAttendances(new ArrayList<>());

        BaseUser clientUser = new BaseUser();
        clientUser.setId(clientId);
        clientUser.setFirstName("John");
        client = new Client(clientUser);

        ServiceItem si = new ServiceItem();
        si.setId(UUID.randomUUID());
        si.setCustomName("Corte");
        si.setPrice(BigDecimal.valueOf(30));
        si.setEstimatedMinutes(30);
        si.setProfessional(professional);
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

    private AttendanceRequest request() {
        return new AttendanceRequest(
                professionalId, establishmentId, clientId,
                List.of(UUID.randomUUID()),
                AttendanceStatus.SCHEDULED,
                OffsetDateTime.now(), 30, OffsetDateTime.now());
    }

    @Nested
    @DisplayName("createAttendance(request, professional, establishment, client)")
    class CreateFourArg {

        @Test
        @DisplayName("sucesso: SCHEDULED, multa pendente cobrada no ingresso")
        void success() {
            when(serviceItemRepository.findAllById(any())).thenReturn(serviceItems);
            when(attendanceRepository.save(any())).thenAnswer(inv -> {
                Attendance a = inv.getArgument(0);
                a.setId(attendanceId);
                return a;
            });

            Attendance saved = attendanceService.createAttendance(request(), professional, establishment, client);

            assertThat(saved.getStatus()).isEqualTo(AttendanceStatus.SCHEDULED);
            assertThat(saved.getClient()).isSameAs(client);
            assertThat(saved.getServiceItems()).isEqualTo(serviceItems);
            verify(clientPenaltyService).chargeOnIngress(saved);
        }

        @Test
        @DisplayName("lock das refeicoes falhando nao derruba a criacao (best-effort)")
        void lockFailure_isSwallowed() {
            when(serviceItemRepository.findAllById(any())).thenReturn(serviceItems);
            when(attendanceRepository.save(any())).thenAnswer(inv -> {
                Attendance a = inv.getArgument(0);
                a.setId(attendanceId);
                return a;
            });
            when(attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(professionalId, establishmentId))
                    .thenThrow(new IllegalStateException("banco fora"));

            Attendance saved = attendanceService.createAttendance(request(), professional, establishment, client);

            assertThat(saved.getId()).isEqualTo(attendanceId);
            assertThat(saved.getPromisedTime()).isNull();
        }

        @Test
        @DisplayName("sem servicos encontrados -> ServiceItemNotFoundException")
        void noServiceItems() {
            when(serviceItemRepository.findAllById(any())).thenReturn(List.of());

            assertThatThrownBy(() -> attendanceService.createAttendance(request(), professional, establishment, client))
                    .isInstanceOf(ServiceItemNotFoundException.class);
            verify(attendanceRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("createIntention — guardas")
    class CreateIntentionGuards {

        private final OffsetDateTime desired = OffsetDateTime.now().plusDays(1);

        @Test
        @DisplayName("profissional sem loja -> ProfessionalNotAffiliatedException")
        void notAffiliated() {
            professional.setEstablishment(null);
            when(professionalRepository.findById(professionalId)).thenReturn(Optional.of(professional));

            assertThatThrownBy(() -> attendanceService.createIntention(
                    professionalId, List.of(UUID.randomUUID()), desired, clientId))
                    .isInstanceOf(ProfessionalNotAffiliatedException.class);
        }

        @Test
        @DisplayName("loja desativada -> EstablishmentUnavailableException")
        void establishmentDeactivated() {
            establishment.setStatus(EstablishmentStatus.DEACTIVATED);
            when(professionalRepository.findById(professionalId)).thenReturn(Optional.of(professional));

            assertThatThrownBy(() -> attendanceService.createIntention(
                    professionalId, List.of(UUID.randomUUID()), desired, clientId))
                    .isInstanceOf(EstablishmentUnavailableException.class);
            verify(clientRepository, never()).findById(any());
        }

        @Test
        @DisplayName("sem servicos encontrados -> ServiceItemNotFoundException")
        void noServiceItems() {
            when(professionalRepository.findById(professionalId)).thenReturn(Optional.of(professional));
            when(clientRepository.findById(clientId)).thenReturn(Optional.of(client));
            when(serviceItemRepository.findAllById(any())).thenReturn(List.of());

            assertThatThrownBy(() -> attendanceService.createIntention(
                    professionalId, List.of(UUID.randomUUID()), desired, clientId))
                    .isInstanceOf(ServiceItemNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("cancelAttendance(id, clientId, ciencia)")
    class CancelByClient {

        @Test
        @DisplayName("ja cancelado pelo cliente -> AttendanceAlreadyCancelledException")
        void alreadyCancelled() {
            attendance.setStatus(AttendanceStatus.CANCELLED);
            when(attendanceRepository.findByIdEager(attendanceId)).thenReturn(Optional.of(attendance));

            assertThatThrownBy(() -> attendanceService.cancelAttendance(attendanceId, clientId, null))
                    .isInstanceOf(AttendanceAlreadyCancelledException.class);
            verify(attendanceRepository, never()).save(any());
        }

        @Test
        @DisplayName("ja retirado pela loja -> AttendanceAlreadyCancelledException")
        void alreadyRemoved() {
            attendance.setStatus(AttendanceStatus.REMOVED);
            when(attendanceRepository.findByIdEager(attendanceId)).thenReturn(Optional.of(attendance));

            assertThatThrownBy(() -> attendanceService.cancelAttendance(attendanceId, clientId, null))
                    .isInstanceOf(AttendanceAlreadyCancelledException.class);
        }

        @Test
        @DisplayName("em atendimento -> AttendanceCancelNotAllowedException")
        void inProgress() {
            attendance.setStatus(AttendanceStatus.IN_PROGRESS);
            when(attendanceRepository.findByIdEager(attendanceId)).thenReturn(Optional.of(attendance));

            assertThatThrownBy(() -> attendanceService.cancelAttendance(attendanceId, clientId, null))
                    .isInstanceOf(AttendanceCancelNotAllowedException.class);
            verify(attendanceRepository, never()).save(any());
        }

        @Test
        @DisplayName("convidado: cancela sem consultar multa")
        void guest_noPenalty() {
            attendance.setClient(null);
            attendance.setGuestName("Convidado");
            when(attendanceRepository.findByIdEager(attendanceId)).thenReturn(Optional.of(attendance));
            when(attendanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            attendanceService.cancelAttendance(attendanceId, clientId, null);

            assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.CANCELLED);
            verifyNoInteractions(clientWaitCalculator, penaltyEventRepository);
        }

        @Test
        @DisplayName("convidado anonimo: cancela tambem")
        void anonymousGuest() {
            attendance.setClient(null);
            when(attendanceRepository.findByIdEager(attendanceId)).thenReturn(Optional.of(attendance));
            when(attendanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            attendanceService.cancelAttendance(attendanceId, clientId, null);

            assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.CANCELLED);
        }
    }

    @Nested
    @DisplayName("cancelAttendance(entity)")
    class CancelEntity {

        @Test
        @DisplayName("cliente com conta -> CANCELLED")
        void withClient() {
            when(attendanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            AttendanceInQueueResponse response = attendanceService.cancelAttendance(attendance);

            assertThat(response.status()).isEqualTo(AttendanceStatus.CANCELLED);
        }

        @Test
        @DisplayName("convidado com nome -> CANCELLED")
        void namedGuest() {
            attendance.setClient(null);
            attendance.setGuestName("Maria");
            when(attendanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThat(attendanceService.cancelAttendance(attendance).status())
                    .isEqualTo(AttendanceStatus.CANCELLED);
        }

        @Test
        @DisplayName("convidado anonimo -> CANCELLED")
        void anonymousGuest() {
            attendance.setClient(null);
            when(attendanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThat(attendanceService.cancelAttendance(attendance).status())
                    .isEqualTo(AttendanceStatus.CANCELLED);
        }
    }

    @Test
    @DisplayName("rescheduleWithTargetTime sem horario -> IllegalArgumentException, sem ir ao banco")
    void reschedule_nullTarget() {
        assertThatThrownBy(() -> attendanceService.rescheduleWithTargetTime(attendanceId, clientId, null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(attendanceRepository);
    }

    @Test
    @DisplayName("removeFromQueue de quem ja terminou -> AttendanceInvalidStatusException")
    void removeFromQueue_finished() {
        attendance.setStatus(AttendanceStatus.FINISHED);
        when(attendanceRepository.findByIdEager(attendanceId)).thenReturn(Optional.of(attendance));

        assertThatThrownBy(() -> attendanceService.removeFromQueue(attendanceId, professionalId))
                .isInstanceOf(AttendanceInvalidStatusException.class);
        verify(attendanceRepository, never()).save(any());
    }

    @Nested
    @DisplayName("getNextAttendance")
    class NextAttendance {

        @Test
        @DisplayName("fila vazia -> null")
        void emptyQueue() {
            assertThat(attendanceService.getNextAttendance(professional, establishment)).isNull();
        }

        @Test
        @DisplayName("devolve o SCHEDULED mais antigo")
        void earliestScheduled() {
            Attendance later = new Attendance();
            later.setId(UUID.randomUUID());
            later.setProfessional(professional);
            later.setEstablishment(establishment);
            later.setGuestName("Depois");
            later.setStatus(AttendanceStatus.SCHEDULED);
            later.setScheduledTime(attendance.getScheduledTime().plusMinutes(10));
            later.setServiceItems(serviceItems);
            professional.getAttendances().addAll(List.of(later, attendance));

            AttendanceInQueueResponse next = attendanceService.getNextAttendance(professional, establishment);

            assertThat(next.id()).isEqualTo(attendanceId);
        }

        @Test
        @DisplayName("convidado com nome ou anonimo na cabeca da fila")
        void guestAtHead() {
            attendance.setClient(null);
            professional.getAttendances().add(attendance);
            assertThat(attendanceService.getNextAttendance(professional, establishment).id()).isEqualTo(attendanceId);

            attendance.setGuestName("Ana");
            assertThat(attendanceService.getNextAttendance(professional, establishment).id()).isEqualTo(attendanceId);
        }
    }

    @Test
    @DisplayName("media semanal: ignora atendimento sem duracao; sem nenhum, 0")
    void weeklyAverage() {
        Attendance a = new Attendance();
        a.setDurationInMinutes(20);
        Attendance b = new Attendance();
        b.setDurationInMinutes(40);
        Attendance semDuracao = new Attendance();
        LocalDate monday = LocalDate.of(2026, 10, 5);
        when(attendanceRepository.findByProfessionalAndStatusAndScheduledTimeBetween(
                eq(professional), eq(AttendanceStatus.FINISHED), any(), any()))
                .thenReturn(List.of(a, b, semDuracao))
                .thenReturn(List.of());

        assertThat(attendanceService.calculateWeeklyAverageDuration(professional, monday, monday.plusDays(6)))
                .isEqualTo(30.0);
        assertThat(attendanceService.calculateWeeklyAverageDuration(professional, monday, monday.plusDays(6)))
                .isZero();
    }

    @Nested
    @DisplayName("builders")
    class Builders {

        @Test
        @DisplayName("buildAttendanceResponse: em andamento ou pausado -> progresso; na fila -> fila")
        void buildAttendanceResponse_byStatus() {
            assertThat(attendanceService.buildAttendanceResponse(attendance))
                    .isInstanceOf(AttendanceInQueueResponse.class);

            attendance.setStatus(AttendanceStatus.IN_PROGRESS);
            attendance.setStartTime(OffsetDateTime.now().minusMinutes(5));
            attendance.setEstimatedMinutes(30);
            assertThat(attendanceService.buildAttendanceResponse(attendance))
                    .isInstanceOf(AttendanceInProgressResponse.class);

            attendance.setStatus(AttendanceStatus.PAUSED);
            attendance.setLastPauseTime(OffsetDateTime.now().minusMinutes(1));
            assertThat(attendanceService.buildAttendanceResponse(attendance))
                    .isInstanceOf(AttendanceInProgressResponse.class);
        }

        @Test
        @DisplayName("progresso sem inicio e fora de IN_PROGRESS -> AttendanceInvalidStatusException")
        void inProgress_withoutStart() {
            attendance.setStartTime(null);

            assertThatThrownBy(() -> attendanceService.buildAttendanceInProgressResponse(attendance))
                    .isInstanceOf(AttendanceInvalidStatusException.class);
        }

        @Test
        @DisplayName("pausado: nulo -> AttendanceNotFoundException; fora de PAUSED -> AttendanceInvalidStatusException")
        void paused_guards() {
            assertThatThrownBy(() -> attendanceService.buildPausedAttendance(null))
                    .isInstanceOf(AttendanceNotFoundException.class);

            attendance.setStatus(AttendanceStatus.IN_PROGRESS);
            assertThatThrownBy(() -> attendanceService.buildPausedAttendance(attendance))
                    .isInstanceOf(AttendanceInvalidStatusException.class);
        }
    }

    @Nested
    @DisplayName("listAttendancesPage")
    class ListPage {

        @Test
        @DisplayName("cliente: lista o proprio historico e corta em memoria")
        void client_slicesInMemory() {
            Attendance second = new Attendance();
            second.setId(UUID.randomUUID());
            second.setProfessional(professional);
            second.setEstablishment(establishment);
            second.setClient(client);
            second.setStatus(AttendanceStatus.FINISHED);
            second.setScheduledTime(OffsetDateTime.now().minusDays(1));
            second.setServiceItems(serviceItems);
            when(clientRepository.findById(clientId)).thenReturn(Optional.of(client));
            when(attendanceRepository.findByClientFilters(eq(clientId), isNull(), any(), any()))
                    .thenReturn(List.of(attendance, second));

            var page = attendanceService.listAttendancesPage(
                    clientId, null, null, clientId, null, null, null, new ShowcasePage(0, 1));

            assertThat(page.items()).hasSize(1);
            assertThat(page.totalItems()).isEqualTo(2);
            assertThat(page.totalPages()).isEqualTo(2);
            assertThat(page.hasNext()).isTrue();
        }

        @Test
        @DisplayName("profissional com datas e pagina vazia: nao busca detalhes")
        void professional_emptyPage_withDates() {
            when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));
            when(attendanceRepository.findIdPageByFilters(isNull(), eq(professionalId), isNull(), any(), any(), any()))
                    .thenReturn(Page.empty());

            var page = attendanceService.listAttendancesPage(
                    professionalId, null, professionalId, null,
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null, new ShowcasePage(0, 20));

            assertThat(page.items()).isEmpty();
            assertThat(page.totalItems()).isZero();
            verify(attendanceRepository, never()).findAllByIdsWithDetails(any());
        }
    }

    @Nested
    @DisplayName("listAttendances — quem pode listar")
    class ListAccess {

        @Test
        @DisplayName("professionalId de outro -> EstablishmentAccessDeniedException")
        void otherProfessional() {
            when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));

            assertThatThrownBy(() -> attendanceService.listAttendances(
                    professionalId, null, UUID.randomUUID(), null, null, null, null))
                    .isInstanceOf(EstablishmentAccessDeniedException.class);
        }

        @Test
        @DisplayName("professionalId sem ser profissional -> EstablishmentAccessDeniedException")
        void notAProfessional() {
            when(professionalRepository.findByIdWithBaseUser(clientId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> attendanceService.listAttendances(
                    clientId, null, professionalId, null, null, null, null))
                    .isInstanceOf(EstablishmentAccessDeniedException.class);
        }

        @Test
        @DisplayName("sem filtro e sem ser profissional -> EstablishmentAccessDeniedException")
        void noFilter_notAProfessional() {
            when(professionalRepository.findByIdWithBaseUser(clientId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> attendanceService.listAttendances(
                    clientId, null, null, null, null, null, null))
                    .isInstanceOf(EstablishmentAccessDeniedException.class);
        }

        @Test
        @DisplayName("sem filtro e profissional sem loja -> EstablishmentAccessDeniedException")
        void noFilter_professionalWithoutEstablishment() {
            professional.setEstablishment(null);
            when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));

            assertThatThrownBy(() -> attendanceService.listAttendances(
                    professionalId, null, null, null, null, null, null))
                    .isInstanceOf(EstablishmentAccessDeniedException.class);
        }

        @Test
        @DisplayName("sem filtro: profissional lista a fila da propria loja")
        void noFilter_ownEstablishment() {
            when(professionalRepository.findByIdWithBaseUser(professionalId)).thenReturn(Optional.of(professional));
            when(attendanceRepository.findByFilters(eq(establishmentId), isNull(), isNull(), any(), any()))
                    .thenReturn(List.of(attendance));

            assertThat(attendanceService.listAttendances(professionalId, null, null, null, null, null, null))
                    .hasSize(1);
        }
    }
}
