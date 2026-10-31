package com.ospaulos.vupth.unit.fitin;

import com.ospaulos.vupth.domain.attendance.fitin.exception.FitInException;
import com.ospaulos.vupth.domain.attendance.fitin.model.FitInRequest;
import com.ospaulos.vupth.domain.attendance.fitin.model.FitInStatus;
import com.ospaulos.vupth.domain.attendance.fitin.repository.FitInRequestRepository;
import com.ospaulos.vupth.domain.attendance.fitin.service.FitInRequestService;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.attendance.service.AttendanceService;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.client.repository.ClientRepository;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.service.ProfessionalService;
import com.ospaulos.vupth.domain.professional.valueObject.OperationMode;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
import static org.mockito.Mockito.when;

/** encaixe-diluido (DEC-136 a 141): a negociacao do adicional, o aceite que encaixa na fila, o vencimento e o sigilo. */
class FitInRequestServiceTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    private final FitInRequestRepository repository = mock(FitInRequestRepository.class);
    private final ProfessionalRepository professionalRepository = mock(ProfessionalRepository.class);
    private final ClientRepository clientRepository = mock(ClientRepository.class);
    private final ServiceItemRepository serviceItemRepository = mock(ServiceItemRepository.class);
    private final AttendanceRepository attendanceRepository = mock(AttendanceRepository.class);
    private final AttendanceService attendanceService = mock(AttendanceService.class);
    private final ProfessionalService professionalService = mock(ProfessionalService.class);
    private final PushNotificationPublisher push = mock(PushNotificationPublisher.class);
    private FitInRequestService service;

    private final UUID clientId = UUID.randomUUID();
    private final UUID professionalId = UUID.randomUUID();
    private final UUID strangerId = UUID.randomUUID();
    private final OffsetDateTime now = OffsetDateTime.parse("2026-11-02T10:00:00-03:00");
    private Professional professional;
    private Client client;
    private ServiceItem item;

    @BeforeEach
    void setUp() {
        service = new FitInRequestService(repository, professionalRepository, clientRepository, serviceItemRepository,
                attendanceRepository, attendanceService, professionalService, push);
        ReflectionTestUtils.invokeMethod(service, "setClock", Clock.fixed(now.toInstant(), SP));
        professional = new Professional(new BaseUser("p@x.com"));
        professional.setId(professionalId);
        professional.setOperationMode(OperationMode.QUEUE);
        Establishment establishment = new Establishment();
        establishment.setId(UUID.randomUUID());
        professional.setEstablishment(establishment);
        client = new Client(new BaseUser("c@x.com"));
        client.setId(clientId);
        item = new ServiceItem();
        item.setId(UUID.randomUUID());
        item.setProfessional(professional);
        when(professionalRepository.findById(professionalId)).thenReturn(Optional.of(professional));
        when(clientRepository.findById(clientId)).thenReturn(Optional.of(client));
        when(serviceItemRepository.findAllById(any())).thenReturn(List.of(item));
        when(repository.save(any(FitInRequest.class))).thenAnswer(i -> i.getArgument(0));
    }

    private FitInRequestService.CreateCommand command(BigDecimal extra) {
        return new FitInRequestService.CreateCommand(professionalId, List.of(item.getId()), 4, now.plusHours(2), extra);
    }

    private FitInRequest stored(FitInRequestService.View view) {
        FitInRequest r = new FitInRequest();
        r.setId(view.id());
        r.setClientId(clientId);
        r.setProfessionalId(professionalId);
        r.setEstablishmentId(professional.getEstablishment().getId());
        r.setServiceItemIds(item.getId().toString());
        r.setAheadCount(view.aheadCount());
        r.setDesiredTime(view.desiredTime());
        r.setCurrentExtra(view.currentExtra());
        r.setLastOfferBy(view.lastOfferBy());
        r.setStatus(view.status());
        r.setCreatedAt(view.createdAt());
        when(repository.findById(view.id())).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    @DisplayName("pedir: nasce PENDING, o profissional e avisado; so hoje, num horario que nao passou; adicional nao negativo; so servico dele; um aberto por vez")
    void create() {
        var view = service.create(clientId, command(new BigDecimal("10")));
        assertThat(view.status()).isEqualTo(FitInStatus.PENDING);
        assertThat(view.currentExtra()).isEqualByComparingTo("10.00");
        assertThat(view.lastOfferBy()).isEqualTo("CLIENT");
        verify(push).notifyUser(eq(professionalId), any(), any(), anyMap());

        assertThatThrownBy(() -> service.create(clientId, new FitInRequestService.CreateCommand(professionalId, List.of(item.getId()), 4,
                now.minusMinutes(1), BigDecimal.TEN))).isInstanceOf(FitInException.class).hasMessageContaining("hoje");
        assertThatThrownBy(() -> service.create(clientId, new FitInRequestService.CreateCommand(professionalId, List.of(item.getId()), 4,
                now.plusDays(1), BigDecimal.TEN))).hasMessageContaining("hoje");
        assertThatThrownBy(() -> service.create(clientId, command(new BigDecimal("-1")))).hasMessageContaining("negativo");
        item.setProfessional(new Professional(new BaseUser("o@x.com")));
        assertThatThrownBy(() -> service.create(clientId, command(BigDecimal.TEN))).hasMessageContaining("servicos deste profissional");
        item.setProfessional(professional);
        when(repository.existsByClientIdAndProfessionalIdAndStatusIn(eq(clientId), eq(professionalId), any())).thenReturn(true);
        assertThatThrownBy(() -> service.create(clientId, command(BigDecimal.TEN))).hasMessageContaining("ja tem um pedido");
        professional.setOperationMode(OperationMode.AGENDA_ONLY);
        when(repository.existsByClientIdAndProfessionalIdAndStatusIn(any(), any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.create(clientId, command(BigDecimal.TEN))).hasMessageContaining("fila viva");
    }

    @Test
    @DisplayName("negociar: o profissional contrapoe (COUNTERED), o cliente contrapoe de volta (PENDING) e aceita; cada um so na sua vez")
    void negotiation() {
        var created = service.create(clientId, command(new BigDecimal("10")));
        FitInRequest r = stored(created);

        assertThatThrownBy(() -> service.counter(created.id(), clientId, new BigDecimal("8"))).hasMessageContaining("outra parte");
        var countered = service.counter(created.id(), professionalId, new BigDecimal("20"));
        assertThat(countered.status()).isEqualTo(FitInStatus.COUNTERED);
        assertThat(countered.lastOfferBy()).isEqualTo("PROFESSIONAL");
        verify(push).notifyUser(eq(clientId), any(), any(), anyMap());
        assertThatThrownBy(() -> service.accept(created.id(), professionalId)).hasMessageContaining("outra parte");

        var back = service.counter(created.id(), clientId, new BigDecimal("15"));
        assertThat(back.status()).isEqualTo(FitInStatus.PENDING);
        assertThat(r.getCurrentExtra()).isEqualByComparingTo("15.00");
        assertThatThrownBy(() -> service.counter(created.id(), professionalId, new BigDecimal("-1"))).hasMessageContaining("negativo");
    }

    @Test
    @DisplayName("aceitar: cria o atendimento na fila e o encaixa depois de N clientes; o adicional vira final e se divide 50/50 (R$ 15,01 -> 7,50 e 7,51)")
    void acceptFitsInTheQueue() {
        var created = service.create(clientId, command(new BigDecimal("15.01")));
        stored(created);
        Attendance attendance = new Attendance();
        attendance.setId(UUID.randomUUID());
        when(attendanceService.createScheduledFromCutRequest(any(), any(), any(), any())).thenReturn(attendance);

        var accepted = service.accept(created.id(), professionalId);

        assertThat(accepted.status()).isEqualTo(FitInStatus.ACCEPTED);
        assertThat(accepted.finalExtra()).isEqualByComparingTo("15.01");
        assertThat(accepted.professionalShare()).isEqualByComparingTo("7.50");
        assertThat(accepted.appShare()).isEqualByComparingTo("7.51");
        assertThat(accepted.attendanceId()).isEqualTo(attendance.getId());
        verify(professionalService).fitInAttendance(professionalId, attendance.getId(), 4);
        verify(push).notifyUser(eq(clientId), any(), any(), anyMap());
        assertThatThrownBy(() -> service.accept(created.id(), professionalId)).hasMessageContaining("ja foi decidido");
    }

    @Test
    @DisplayName("recusar (so o profissional) e cancelar (so o cliente) fecham o pedido e avisam a outra parte; nada entra na fila")
    void declineAndCancel() {
        var one = service.create(clientId, command(BigDecimal.TEN));
        stored(one);
        assertThatThrownBy(() -> service.decline(one.id(), clientId)).hasMessageContaining("outra parte");
        assertThat(service.decline(one.id(), professionalId).status()).isEqualTo(FitInStatus.DECLINED);
        verify(push).notifyUser(eq(clientId), any(), any(), anyMap());

        var two = service.create(clientId, command(BigDecimal.TEN));
        stored(two);
        assertThatThrownBy(() -> service.cancel(two.id(), professionalId)).hasMessageContaining("outra parte");
        assertThat(service.cancel(two.id(), clientId).status()).isEqualTo(FitInStatus.CANCELLED);
        verify(attendanceService, never()).createScheduledFromCutRequest(any(), any(), any(), any());
    }

    @Test
    @DisplayName("o horario que o cliente quis chegar passou: o pedido vence e nao aceita mais")
    void expiresAtDesiredTime() {
        var created = service.create(clientId, command(BigDecimal.TEN));
        FitInRequest r = stored(created);
        r.setDesiredTime(now.minusMinutes(1));

        assertThatThrownBy(() -> service.accept(created.id(), professionalId)).hasMessageContaining("venceu");
        assertThat(r.getStatus()).isEqualTo(FitInStatus.EXPIRED);
        assertThat(service.get(created.id(), clientId).status()).isEqualTo(FitInStatus.EXPIRED);
    }

    @Test
    @DisplayName("sigilo (DEC-141): outro usuario recebe o mesmo 404 do id inexistente em todas as rotas, e o apoio e so do profissional")
    void secrecy() {
        var created = service.create(clientId, command(BigDecimal.TEN));
        stored(created);
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());

        for (Runnable call : List.<Runnable>of(
                () -> service.get(created.id(), strangerId), () -> service.accept(created.id(), strangerId),
                () -> service.decline(created.id(), strangerId), () -> service.cancel(created.id(), strangerId),
                () -> service.counter(created.id(), strangerId, BigDecimal.ONE), () -> service.support(created.id(), strangerId),
                () -> service.get(missing, clientId))) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(FitInException.class,
                    e -> assertThat(e.getCode()).isEqualTo("FIT_IN_NOT_FOUND"));
        }
        assertThatThrownBy(() -> service.support(created.id(), clientId)).isInstanceOfSatisfying(FitInException.class,
                e -> assertThat(e.getCode()).isEqualTo("FIT_IN_NOT_FOUND"));
        verify(push, never()).notifyUser(eq(strangerId), any(), any(), anyMap());
    }

    @Test
    @DisplayName("apoio: fatia por cliente, se passa despercebida (ate 10 min) e se o encaixe passa do fechamento")
    void support() {
        var created = service.create(clientId, command(BigDecimal.TEN));
        stored(created);
        item.setEstimatedMinutes(30);
        Attendance a = mock(Attendance.class);
        when(a.getEstimatedTotalMinutes()).thenReturn(30);
        when(attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(any(), any()))
                .thenReturn(List.of(a, a, a, a, a, a));

        var s = service.support(created.id(), professionalId);

        assertThat(s.aheadNow()).isEqualTo(4);
        assertThat(s.behindNow()).isEqualTo(2);
        assertThat(s.durationMinutes()).isEqualTo(30);
        assertThat(s.sliceMinutesPerClient()).isEqualTo(8);
        assertThat(s.absorbedUnnoticed()).isTrue();
        assertThat(s.estimatedFinish()).isEqualTo(now.plusMinutes(6 * 30 + 30));
    }
}
