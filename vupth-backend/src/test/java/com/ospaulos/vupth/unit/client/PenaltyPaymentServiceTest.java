package com.ospaulos.vupth.unit.client;

import com.ospaulos.vupth.domain.client.exception.PenaltyPaymentException;
import com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent;
import com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository;
import com.ospaulos.vupth.domain.client.repository.ClientRepository;
import com.ospaulos.vupth.domain.client.repository.PenaltyPaymentRepository;
import com.ospaulos.vupth.domain.client.service.ClientPenaltyService;
import com.ospaulos.vupth.domain.client.service.PenaltyPaymentService;
import com.ospaulos.vupth.infrastructure.config.AppPix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.ospaulos.vupth.domain.client.model.PenaltyPayment;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** pagar-multa-avulso: sem a chave Pix do app a multa nao gera Pix; o AppPix so liga com a chave. */
class PenaltyPaymentServiceTest {

    @Test
    @DisplayName("sem APP_PIX_KEY: 503 PENALTY_PIX_NOT_CONFIGURED e nada e criado")
    void notConfigured() {
        ClientPenaltyService penalties = mock(ClientPenaltyService.class);
        PenaltyPaymentRepository payments = mock(PenaltyPaymentRepository.class);
        UUID client = UUID.randomUUID();
        ClientPenaltyEvent event = new ClientPenaltyEvent(null, null, null, 0, new BigDecimal("5.00"));
        when(penalties.pendingFor(client)).thenReturn(List.of(event));
        var service = new PenaltyPaymentService(payments, mock(ClientPenaltyEventRepository.class), penalties,
                mock(ClientRepository.class), new AppPix("", "VUPTH", "SAO PAULO"),
                new com.ospaulos.vupth.infrastructure.config.PaymentMode("SPLIT_PIX"));

        assertThatThrownBy(() -> service.pay(client)).isInstanceOfSatisfying(PenaltyPaymentException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.getCode()).isEqualTo("PENALTY_PIX_NOT_CONFIGURED");
        });
        org.mockito.Mockito.verifyNoInteractions(payments);
    }

    @Test
    @DisplayName("AppPix: sem chave nao gera o copia-e-cola; com chave gera um Pix valido com o valor")
    void appPix() {
        assertThat(new AppPix(" ", "VUPTH", "SAO PAULO").configured()).isFalse();
        assertThat(new AppPix(null, "VUPTH", "SAO PAULO").copyPaste(new BigDecimal("5"), "X")).isNull();

        AppPix pix = new AppPix("+5511988887777", "VUPTH", "SAO PAULO");
        assertThat(pix.configured()).isTrue();
        assertThat(pix.holderName()).isEqualTo("VUPTH");
        assertThat(pix.copyPaste(new BigDecimal("8.00"), "VUPTHMULTA1")).startsWith("000201").contains("5404" + "8.00");
    }

    @Test
    @DisplayName("fase de testes sem gateway (TEST_PIX_TOTAL, o padrao): pagar a multa avulso responde 409 PENALTY_PAY_DISABLED")
    void disabledInTestMode() {
        PenaltyPaymentRepository payments = mock(PenaltyPaymentRepository.class);
        var service = new PenaltyPaymentService(payments, mock(ClientPenaltyEventRepository.class),
                mock(ClientPenaltyService.class), mock(ClientRepository.class),
                new AppPix("+5511988887777", "VUPTH", "SAO PAULO"),
                new com.ospaulos.vupth.infrastructure.config.PaymentMode("TEST_PIX_TOTAL"));

        assertThatThrownBy(() -> service.pay(UUID.randomUUID())).isInstanceOfSatisfying(PenaltyPaymentException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getCode()).isEqualTo("PENALTY_PAY_DISABLED");
        });
        org.mockito.Mockito.verifyNoInteractions(payments);
    }

    @Test
    @DisplayName("PaymentMode: so SPLIT_PIX liga os dois Pix; nulo, vazio ou outro valor e a fase de testes")
    void paymentMode() {
        assertThat(new com.ospaulos.vupth.infrastructure.config.PaymentMode("split_pix").splitPix()).isTrue();
        assertThat(new com.ospaulos.vupth.infrastructure.config.PaymentMode("SPLIT_PIX").name()).isEqualTo("SPLIT_PIX");
        assertThat(new com.ospaulos.vupth.infrastructure.config.PaymentMode(null).splitPix()).isFalse();
        assertThat(new com.ospaulos.vupth.infrastructure.config.PaymentMode(" ").name()).isEqualTo("TEST_PIX_TOTAL");
        assertThat(new com.ospaulos.vupth.infrastructure.config.PaymentMode("qualquer").splitPix()).isFalse();
    }

    // ─── testes-de-pagamento-massivos (DEC-92): os ramos do pagamento avulso ───

    private final PenaltyPaymentRepository payments = mock(PenaltyPaymentRepository.class);
    private final ClientPenaltyEventRepository events = mock(ClientPenaltyEventRepository.class);
    private final ClientPenaltyService penalties = mock(ClientPenaltyService.class);
    private final ClientRepository clients = mock(ClientRepository.class);

    private PenaltyPaymentService splitService() {
        return new PenaltyPaymentService(payments, events, penalties, clients,
                new AppPix("+5511988887777", "VUPTH", "SAO PAULO"),
                new com.ospaulos.vupth.infrastructure.config.PaymentMode("SPLIT_PIX"));
    }

    private static PenaltyPayment payment(UUID client, PenaltyPayment.Status status, OffsetDateTime created, OffsetDateTime expires) {
        PenaltyPayment p = new PenaltyPayment();
        p.setId(UUID.randomUUID());
        p.setClientId(client);
        p.setAmount(new BigDecimal("5.00"));
        p.setStatus(status);
        p.setCreatedAt(created);
        p.setExpiresAt(expires);
        return p;
    }

    @Test
    @DisplayName("pagar sem multa pendente: devolve o pagamento vigente mais novo do cliente (nao o de outro, o vencido nem o pago); sem nenhum -> 409 PENALTY_NOTHING_TO_PAY")
    void payWithNothingPending() {
        UUID client = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        PenaltyPayment older = payment(client, PenaltyPayment.Status.PENDING, now.minusHours(3), now.plusHours(21));
        PenaltyPayment newer = payment(client, PenaltyPayment.Status.PENDING, now.minusHours(1), now.plusHours(23));
        PenaltyPayment expired = payment(client, PenaltyPayment.Status.PENDING, now.minusHours(30), now.minusHours(6));
        PenaltyPayment paid = payment(client, PenaltyPayment.Status.PAID, now.minusMinutes(5), now.plusHours(24));
        PenaltyPayment other = payment(UUID.randomUUID(), PenaltyPayment.Status.PENDING, now, now.plusHours(24));
        when(penalties.pendingFor(client)).thenReturn(List.of());
        when(payments.findAll()).thenReturn(List.of(older, newer, expired, paid, other));

        var response = splitService().pay(client);

        assertThat(response.id()).isEqualTo(newer.getId());
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.pixCopyPaste()).startsWith("000201");
        assertThat(response.holderName()).isEqualTo("VUPTH");
        assertThat(response.clientId()).isNull();

        when(payments.findAll()).thenReturn(List.of(expired, paid, other));
        assertThatThrownBy(() -> splitService().pay(client)).isInstanceOfSatisfying(PenaltyPaymentException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getCode()).isEqualTo("PENALTY_NOTHING_TO_PAY");
        });
    }

    @Test
    @DisplayName("pagar com multas pendentes: um pagamento so, com a soma, valendo 24 h, e as multas ficam ligadas a ele")
    void payWithPending() {
        UUID client = UUID.randomUUID();
        ClientPenaltyEvent a = new ClientPenaltyEvent(null, null, null, 0, new BigDecimal("5.00"));
        ClientPenaltyEvent b = new ClientPenaltyEvent(null, null, null, 0, new BigDecimal("3.00"));
        when(penalties.pendingFor(client)).thenReturn(List.of(a, b));

        var response = splitService().pay(client);

        assertThat(response.amount()).isEqualByComparingTo("8.00");
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.expiresAt()).isAfter(response.createdAt().plusHours(23));
        assertThat(a.getPenaltyPaymentId()).isEqualTo(response.id());
        assertThat(b.getPenaltyPaymentId()).isEqualTo(response.id());
        org.mockito.Mockito.verify(events).saveAll(List.of(a, b));
    }

    @Test
    @DisplayName("ler um pagamento: so o dono dele; vencido vira EXPIRED sem Pix; o painel ve o cliente")
    void getAndAwaiting() {
        UUID client = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        PenaltyPayment expired = payment(client, PenaltyPayment.Status.PENDING, now.minusHours(30), now.minusHours(6));
        when(payments.findById(expired.getId())).thenReturn(Optional.of(expired));
        com.ospaulos.vupth.domain.client.model.Client who = mock(com.ospaulos.vupth.domain.client.model.Client.class);
        when(who.getFirstName()).thenReturn("Ana");
        when(clients.findById(client)).thenReturn(Optional.of(who));
        when(payments.findByStatusOrderByCreatedAtAsc(PenaltyPayment.Status.PENDING)).thenReturn(List.of(expired));

        var mine = splitService().get(client, expired.getId());
        assertThat(mine.status()).isEqualTo("EXPIRED");
        assertThat(mine.pixCopyPaste()).isNull();
        assertThat(mine.holderName()).isNull();
        assertThatThrownBy(() -> splitService().get(UUID.randomUUID(), expired.getId()))
                .isInstanceOfSatisfying(PenaltyPaymentException.class, e -> assertThat(e.getCode()).isEqualTo("PENALTY_PAYMENT_NOT_FOUND"));
        assertThatThrownBy(() -> splitService().get(client, UUID.randomUUID()))
                .isInstanceOfSatisfying(PenaltyPaymentException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        var forAdmin = splitService().awaitingConfirmation();
        assertThat(forAdmin).hasSize(1);
        assertThat(forAdmin.get(0).clientId()).isEqualTo(client);
        assertThat(forAdmin.get(0).clientName()).isEqualTo("Ana");

        when(clients.findById(client)).thenReturn(Optional.empty());
        assertThat(splitService().awaitingConfirmation().get(0).clientName()).isNull();
    }

    @Test
    @DisplayName("painel confirma: inexistente 404, ja pago idempotente, vencido com as multas livres confirma, vencido com multa ja cobrada ou sem multa -> 409 PENALTY_PAYMENT_EXPIRED")
    void confirmBranches() {
        UUID client = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        var service = splitService();

        UUID missing = UUID.randomUUID();
        when(payments.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.confirm(missing, admin))
                .isInstanceOfSatisfying(PenaltyPaymentException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        PenaltyPayment paid = payment(client, PenaltyPayment.Status.PAID, now.minusHours(2), now.plusHours(22));
        paid.setPaidAt(now.minusHours(1));
        when(payments.findById(paid.getId())).thenReturn(Optional.of(paid));
        assertThat(service.confirm(paid.getId(), admin).status()).isEqualTo("PAID");
        org.mockito.Mockito.verify(payments, org.mockito.Mockito.never()).save(paid);

        PenaltyPayment free = payment(client, PenaltyPayment.Status.PENDING, now.minusHours(30), now.minusHours(6));
        ClientPenaltyEvent freeEvent = new ClientPenaltyEvent(null, null, null, 0, new BigDecimal("5.00"));
        when(payments.findById(free.getId())).thenReturn(Optional.of(free));
        when(events.findByPenaltyPaymentId(free.getId())).thenReturn(List.of(freeEvent));
        var confirmed = service.confirm(free.getId(), admin);
        assertThat(confirmed.status()).isEqualTo("PAID");
        assertThat(free.getConfirmedBy()).isEqualTo(admin);
        assertThat(free.getPaidAt()).isNotNull();

        PenaltyPayment charged = payment(client, PenaltyPayment.Status.PENDING, now.minusHours(30), now.minusHours(6));
        ClientPenaltyEvent chargedEvent = new ClientPenaltyEvent(null, null, null, 0, new BigDecimal("5.00"));
        chargedEvent.setChargedAttendanceId(UUID.randomUUID());
        when(payments.findById(charged.getId())).thenReturn(Optional.of(charged));
        when(events.findByPenaltyPaymentId(charged.getId())).thenReturn(List.of(chargedEvent));
        assertThatThrownBy(() -> service.confirm(charged.getId(), admin))
                .isInstanceOfSatisfying(PenaltyPaymentException.class, e -> assertThat(e.getCode()).isEqualTo("PENALTY_PAYMENT_EXPIRED"));

        PenaltyPayment empty = payment(client, PenaltyPayment.Status.PENDING, now.minusHours(30), now.minusHours(6));
        when(payments.findById(empty.getId())).thenReturn(Optional.of(empty));
        when(events.findByPenaltyPaymentId(empty.getId())).thenReturn(List.of());
        assertThatThrownBy(() -> service.confirm(empty.getId(), admin))
                .isInstanceOfSatisfying(PenaltyPaymentException.class, e -> assertThat(e.getCode()).isEqualTo("PENALTY_PAYMENT_EXPIRED"));
    }

    @Test
    @DisplayName("pagamento vigente com o Pix do app sem chave: o cliente ve o valor e o status, sem copia-e-cola nem titular")
    void liveWithUnconfiguredAppPix() {
        UUID client = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        PenaltyPayment live = payment(client, PenaltyPayment.Status.PENDING, now.minusHours(1), now.plusHours(23));
        when(payments.findById(live.getId())).thenReturn(Optional.of(live));
        var service = new PenaltyPaymentService(payments, events, penalties, clients, new AppPix("", "VUPTH", "SAO PAULO"),
                new com.ospaulos.vupth.infrastructure.config.PaymentMode("SPLIT_PIX"));

        var response = service.get(client, live.getId());

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.pixCopyPaste()).isNull();
        assertThat(response.holderName()).isNull();
    }
}
