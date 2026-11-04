package com.ospaulos.vupth.unit.client;

import com.ospaulos.vupth.api.client.dto.response.PendingPenaltiesResponse;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent;
import com.ospaulos.vupth.domain.client.model.PenaltyType;
import com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository;
import com.ospaulos.vupth.domain.client.repository.PenaltyPaymentRepository;
import com.ospaulos.vupth.domain.client.service.ClientPenaltyService;
import com.ospaulos.vupth.domain.payment.service.PaymentGatewayService;
import com.ospaulos.vupth.infrastructure.config.PaymentMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** multas-carregam-na-virada (DEC-175): as pendentes carregam na virada, e o total separa o periodo de teste do cobrado pelo app. */
@DisplayName("Multas na virada do gateway: carregam, separadas em teste x app")
class PenaltyCarryOverTest {

    private final ClientPenaltyEventRepository events = mock(ClientPenaltyEventRepository.class);
    private final AttendanceRepository attendances = mock(AttendanceRepository.class);
    private final PenaltyPaymentRepository payments = mock(PenaltyPaymentRepository.class);
    private final PaymentGatewayService gateway = mock(PaymentGatewayService.class);
    private final ClientPenaltyService service = new ClientPenaltyService(events, attendances, payments);
    private final UUID clientId = UUID.randomUUID();
    private final OffsetDateTime changesAt = OffsetDateTime.now().minusDays(1);

    private ClientPenaltyEvent event(String amount, OffsetDateTime createdAt) {
        ClientPenaltyEvent e = new ClientPenaltyEvent(null, PenaltyType.LATE_CANCEL, null, 0, new BigDecimal(amount));
        e.setId(UUID.randomUUID());
        e.setCreatedAt(createdAt);
        return e;
    }

    private void wire(String mode) {
        ReflectionTestUtils.setField(service, "paymentMode", new PaymentMode(mode));
        ReflectionTestUtils.setField(service, "gateway", gateway);
        when(attendances.findAllById(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("passada a virada: a multa de antes fica como teste, a de depois como app; nenhuma e zerada")
    void afterTheSwitch() {
        wire(PaymentMode.TEST_PIX_TOTAL);
        when(gateway.connected()).thenReturn(true);
        when(gateway.changesAtIfConnected()).thenReturn(Optional.of(changesAt));
        ClientPenaltyEvent before = event("5.00", changesAt.minusDays(3));
        ClientPenaltyEvent after = event("3.00", changesAt.plusHours(2));
        when(events.findByClientIdAndAmountIsNotNullOrderByCreatedAtAsc(clientId)).thenReturn(List.of(before, after));

        PendingPenaltiesResponse r = service.pending(clientId);

        assertThat(r.penalties()).hasSize(2);
        assertThat(r.penalties().get(0).testPeriod()).isTrue();
        assertThat(r.penalties().get(1).testPeriod()).isFalse();
        assertThat(r.testTotal()).isEqualByComparingTo("5.00");
        assertThat(r.appTotal()).isEqualByComparingTo("3.00");
        assertThat(r.total()).isEqualByComparingTo("8.00");
    }

    @Test
    @DisplayName("ainda no teste (gateway desligado ou no prazo do aviso): todas sao do periodo de teste")
    void beforeTheSwitch() {
        wire(PaymentMode.TEST_PIX_TOTAL);
        when(gateway.connected()).thenReturn(false);
        when(events.findByClientIdAndAmountIsNotNullOrderByCreatedAtAsc(clientId))
                .thenReturn(List.of(event("5.00", OffsetDateTime.now().minusDays(1))));

        PendingPenaltiesResponse r = service.pending(clientId);

        assertThat(r.penalties().get(0).testPeriod()).isTrue();
        assertThat(r.testTotal()).isEqualByComparingTo("5.00");
        assertThat(r.appTotal()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("app cobrando sem dia de virada (SPLIT_PIX ou sandbox): todas sao do app")
    void appChargesWithoutSwitchDate() {
        wire(PaymentMode.SPLIT_PIX);
        when(gateway.connected()).thenReturn(false);
        when(gateway.changesAtIfConnected()).thenReturn(Optional.empty());
        when(events.findByClientIdAndAmountIsNotNullOrderByCreatedAtAsc(clientId))
                .thenReturn(List.of(event("3.00", OffsetDateTime.now().minusDays(30))));

        PendingPenaltiesResponse r = service.pending(clientId);

        assertThat(r.penalties().get(0).testPeriod()).isFalse();
        assertThat(r.appTotal()).isEqualByComparingTo("3.00");
        assertThat(r.testTotal()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("sem multa: os tres totais zerados")
    void empty() {
        wire(PaymentMode.TEST_PIX_TOTAL);
        when(events.findByClientIdAndAmountIsNotNullOrderByCreatedAtAsc(clientId)).thenReturn(List.of());

        PendingPenaltiesResponse r = service.pending(clientId);

        assertThat(r.total()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(r.testTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(r.appTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
