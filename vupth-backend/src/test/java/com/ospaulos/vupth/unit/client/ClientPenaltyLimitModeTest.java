package com.ospaulos.vupth.unit.client;

import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.client.exception.PenaltyLimitException;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** limite-de-duas-multas (DEC-83/85): desligado na fase de testes, ligado com o SPLIT_PIX ou o gateway conectado. */
@DisplayName("Limite de duas multas: so vale com o SPLIT_PIX ou o gateway conectado")
class ClientPenaltyLimitModeTest {

    private final ClientPenaltyEventRepository events = mock(ClientPenaltyEventRepository.class);
    private final PenaltyPaymentRepository payments = mock(PenaltyPaymentRepository.class);
    private final ClientPenaltyService service = new ClientPenaltyService(events, mock(AttendanceRepository.class), payments);
    private final UUID clientId = UUID.randomUUID();

    private void twoPending() {
        ClientPenaltyEvent a = new ClientPenaltyEvent(null, PenaltyType.LATE_CANCEL, null, 0, new BigDecimal("5.00"));
        a.setId(UUID.randomUUID());
        a.setCreatedAt(java.time.OffsetDateTime.now());
        ClientPenaltyEvent b = new ClientPenaltyEvent(null, PenaltyType.NO_SHOW, null, 0, new BigDecimal("3.00"));
        b.setId(UUID.randomUUID());
        b.setCreatedAt(java.time.OffsetDateTime.now());
        when(events.findByClientIdAndAmountIsNotNullOrderByCreatedAtAsc(clientId)).thenReturn(List.of(a, b));
    }

    @Test
    @DisplayName("fase de testes (TEST_PIX_TOTAL, sem gateway): duas multas e o cliente ainda entra")
    void offInTheTestPhase() {
        twoPending();
        ReflectionTestUtils.setField(service, "paymentMode", new PaymentMode("TEST_PIX_TOTAL"));
        assertThatCode(() -> service.assertWithinLimit(clientId)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SPLIT_PIX: duas multas barram com PENALTY_LIMIT_REACHED")
    void onWithSplitPix() {
        twoPending();
        ReflectionTestUtils.setField(service, "paymentMode", new PaymentMode("SPLIT_PIX"));
        assertThatThrownBy(() -> service.assertWithinLimit(clientId)).isInstanceOf(PenaltyLimitException.class);
    }

    @Test
    @DisplayName("gateway conectado, mesmo em TEST_PIX_TOTAL: o limite liga")
    void onWithTheGateway() {
        twoPending();
        PaymentGatewayService gateway = mock(PaymentGatewayService.class);
        when(gateway.connected()).thenReturn(true);
        ReflectionTestUtils.setField(service, "paymentMode", new PaymentMode("TEST_PIX_TOTAL"));
        ReflectionTestUtils.setField(service, "gateway", gateway);
        assertThatThrownBy(() -> service.assertWithinLimit(clientId)).isInstanceOf(PenaltyLimitException.class);
    }

    @Test
    @DisplayName("max-pending 0 desliga o limite em qualquer modo")
    void zeroTurnsItOff() {
        twoPending();
        ReflectionTestUtils.setField(service, "maxPending", 0);
        ReflectionTestUtils.setField(service, "paymentMode", new PaymentMode("SPLIT_PIX"));
        assertThatCode(() -> service.assertWithinLimit(clientId)).doesNotThrowAnyException();
    }
}
