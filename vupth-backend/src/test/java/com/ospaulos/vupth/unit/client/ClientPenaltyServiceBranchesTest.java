package com.ospaulos.vupth.unit.client;

import com.ospaulos.vupth.api.client.dto.response.PendingPenaltiesResponse;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent;
import com.ospaulos.vupth.domain.client.model.PenaltyPayment;
import com.ospaulos.vupth.domain.client.model.PenaltyType;
import com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository;
import com.ospaulos.vupth.domain.client.repository.PenaltyPaymentRepository;
import com.ospaulos.vupth.domain.client.service.ClientPenaltyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** testes-de-pagamento-massivos (DEC-92): quais multas contam como pendentes e como a origem de cada uma e descrita. */
@DisplayName("Multas pendentes: o que conta, o que ja esta em cobranca ou paga, e a origem")
class ClientPenaltyServiceBranchesTest {

    private final ClientPenaltyEventRepository events = mock(ClientPenaltyEventRepository.class);
    private final AttendanceRepository attendances = mock(AttendanceRepository.class);
    private final PenaltyPaymentRepository payments = mock(PenaltyPaymentRepository.class);
    private final ClientPenaltyService service = new ClientPenaltyService(events, attendances, payments);
    private final UUID clientId = UUID.randomUUID();

    private static ClientPenaltyEvent event(String amount) {
        ClientPenaltyEvent e = new ClientPenaltyEvent(null, PenaltyType.LATE_CANCEL, null, 0,
                amount == null ? null : new BigDecimal(amount));
        e.setId(UUID.randomUUID());
        e.setCreatedAt(OffsetDateTime.parse("2026-10-18T10:00:00-03:00"));
        return e;
    }

    private static Attendance attendance(AttendanceStatus status) {
        Attendance a = new Attendance();
        a.setId(UUID.randomUUID());
        a.setStatus(status);
        a.setScheduledTime(OffsetDateTime.parse("2026-10-17T10:00:00-03:00"));
        return a;
    }

    @Test
    @DisplayName("pendente: sem cobranca, cobrada por atendimento que terminou sem servico, ou coberta por pagamento avulso vencido; fora: valor zero, cobrada por corte concluido e coberta por pagamento vigente ou pago")
    void whatCountsAsPending() {
        ClientPenaltyEvent free = event("5.00");
        ClientPenaltyEvent zero = event("0.00");

        Attendance paidCut = attendance(AttendanceStatus.FINISHED);
        ClientPenaltyEvent charged = event("5.00");
        charged.setChargedAttendanceId(paidCut.getId());

        Attendance leftCut = attendance(AttendanceStatus.CANCELLED);
        ClientPenaltyEvent released = event("3.00");
        released.setChargedAttendanceId(leftCut.getId());

        PenaltyPayment paid = new PenaltyPayment();
        paid.setId(UUID.randomUUID());
        paid.setStatus(PenaltyPayment.Status.PAID);
        paid.setExpiresAt(OffsetDateTime.now().minusDays(1));
        PenaltyPayment live = new PenaltyPayment();
        live.setId(UUID.randomUUID());
        live.setStatus(PenaltyPayment.Status.PENDING);
        live.setExpiresAt(OffsetDateTime.now().plusHours(5));
        PenaltyPayment expired = new PenaltyPayment();
        expired.setId(UUID.randomUUID());
        expired.setStatus(PenaltyPayment.Status.PENDING);
        expired.setExpiresAt(OffsetDateTime.now().minusHours(1));
        ClientPenaltyEvent byPaid = event("5.00");
        byPaid.setPenaltyPaymentId(paid.getId());
        ClientPenaltyEvent byLive = event("5.00");
        byLive.setPenaltyPaymentId(live.getId());
        ClientPenaltyEvent byExpired = event("2.00");
        byExpired.setPenaltyPaymentId(expired.getId());

        when(events.findByClientIdAndAmountIsNotNullOrderByCreatedAtAsc(clientId))
                .thenReturn(List.of(free, zero, charged, released, byPaid, byLive, byExpired));
        when(attendances.findAllById(any())).thenReturn(List.of(paidCut, leftCut));
        when(payments.findAllById(any())).thenReturn(List.of(paid, live, expired));

        List<ClientPenaltyEvent> pending = service.pendingFor(clientId);

        assertThat(pending).containsExactlyInAnyOrder(free, released, byExpired);
        PendingPenaltiesResponse response = service.pending(clientId);
        assertThat(response.total()).isEqualByComparingTo("10.00");
        // a origem sem atendimento carregado cai no horario da multa, sem nome de profissional nem de loja
        assertThat(response.penalties()).allSatisfy(p -> {
            assertThat(p.originProfessionalName()).isNull();
            assertThat(p.originEstablishmentName()).isNull();
        });
    }

    @Test
    @DisplayName("cobrada por atendimento: agrupa por atendimento e descreve a origem pelo horario, sem o profissional nem a loja quando faltam")
    void chargedToDescribesOrigin() {
        Attendance origin = attendance(AttendanceStatus.FINISHED);
        Attendance carrier = attendance(AttendanceStatus.IN_PROGRESS);
        ClientPenaltyEvent e = event("5.00");
        e.setAttendanceId(origin.getId());
        e.setChargedAttendanceId(carrier.getId());
        ClientPenaltyEvent orphan = event("3.00");
        orphan.setChargedAttendanceId(carrier.getId());
        when(events.findByChargedAttendanceIdInOrderByCreatedAtAsc(any())).thenReturn(List.of(e, orphan));
        when(attendances.findAllById(any())).thenReturn(List.of(origin));

        var byAttendance = service.chargedTo(List.of(carrier.getId()));

        assertThat(byAttendance.get(carrier.getId())).hasSize(2);
        assertThat(byAttendance.get(carrier.getId()).get(0).originDate()).isEqualTo(origin.getScheduledTime());
        assertThat(byAttendance.get(carrier.getId()).get(0).originProfessionalName()).isNull();
        assertThat(byAttendance.get(carrier.getId()).get(1).originDate()).isEqualTo(orphan.getCreatedAt());
        assertThat(service.chargedTo(List.of())).isEqualTo(Map.of());
    }
}
