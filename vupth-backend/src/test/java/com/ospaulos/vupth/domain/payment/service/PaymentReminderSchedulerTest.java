package com.ospaulos.vupth.domain.payment.service;

import com.ospaulos.vupth.domain.payment.service.PaymentService.PaymentReminder;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** push-pagamento-pendente (DEC-78): o lembrete das 10h (Sao Paulo) do pagamento ainda pendente. */
class PaymentReminderSchedulerTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    private final PaymentService paymentService = mock(PaymentService.class);
    private final PushNotificationPublisher push = mock(PushNotificationPublisher.class);

    private PaymentReminderScheduler at(String isoInSaoPaulo) {
        Instant instant = ZonedDateTime.parse(isoInSaoPaulo + "[America/Sao_Paulo]").toInstant();
        return new PaymentReminderScheduler(paymentService, push, Clock.fixed(instant, SP));
    }

    @Test
    @DisplayName("o texto leva o valor com virgula")
    void text() {
        assertThat(PaymentReminderScheduler.pendingText(new BigDecimal("12.9")))
                .isEqualTo("Atendimento concluído. Falta o pagamento de R$ 12,90.");
    }

    @Test
    @DisplayName("antes das 10h o proximo disparo e hoje as 10h; depois, amanha as 10h")
    void nextFire() {
        assertThat(at("2026-10-17T08:00:00-03:00").nextFire())
                .isEqualTo(ZonedDateTime.parse("2026-10-17T10:00:00-03:00[America/Sao_Paulo]").toInstant());
        assertThat(at("2026-10-17T10:00:00-03:00").nextFire())
                .isEqualTo(ZonedDateTime.parse("2026-10-18T10:00:00-03:00[America/Sao_Paulo]").toInstant());
        assertThat(at("2026-10-17T15:00:00-03:00").nextFire())
                .isEqualTo(ZonedDateTime.parse("2026-10-18T10:00:00-03:00[America/Sao_Paulo]").toInstant());
    }

    @Test
    @DisplayName("manda um push PAYMENT_PENDING por pagamento pendente de antes de hoje")
    void sends() {
        UUID client = UUID.randomUUID(), att = UUID.randomUUID(), est = UUID.randomUUID();
        when(paymentService.remindersFinishedBefore(any())).thenReturn(
                List.of(new PaymentReminder(client, att, est, new BigDecimal("35.00"))));

        assertThat(at("2026-10-17T10:00:00-03:00").sendReminders()).isEqualTo(1);

        verify(paymentService).remindersFinishedBefore(
                ZonedDateTime.parse("2026-10-17T00:00:00-03:00[America/Sao_Paulo]").toOffsetDateTime());
        verify(push).notifyUser(eq(client), eq("Pagamento pendente"),
                eq("Atendimento concluído. Falta o pagamento de R$ 35,00."),
                eq(Map.of("type", "PAYMENT_PENDING", "attendanceId", att.toString(),
                        "establishmentId", est.toString(), "amount", "35.00")));
    }

    @Test
    @DisplayName("sem pendencia, nao manda nada")
    void nothing() {
        when(paymentService.remindersFinishedBefore(any())).thenReturn(List.of());
        assertThat(at("2026-10-17T10:00:00-03:00").sendReminders()).isZero();
        verifyNoInteractions(push);
    }

    @Test
    @DisplayName("nao depende do relogio real: em varias horas simuladas (virada do dia, fuso, antes e depois das 10h) o proximo disparo e sempre as 10h de Sao Paulo, hoje ou amanha")
    void nextFireAtSimulatedHours() {
        record Case(String now, String expected) {}
        for (Case c : List.of(
                new Case("2026-10-17T00:00:00-03:00", "2026-10-17T10:00:00-03:00"),
                new Case("2026-10-17T09:59:59-03:00", "2026-10-17T10:00:00-03:00"),
                new Case("2026-10-17T10:00:00-03:00", "2026-10-18T10:00:00-03:00"),
                new Case("2026-10-17T10:00:01-03:00", "2026-10-18T10:00:00-03:00"),
                new Case("2026-10-17T23:59:59-03:00", "2026-10-18T10:00:00-03:00"),
                new Case("2026-12-31T23:59:59-03:00", "2027-01-01T10:00:00-03:00"),
                new Case("2028-02-28T12:00:00-03:00", "2028-02-29T10:00:00-03:00"))) {
            assertThat(at(c.now()).nextFire()).as("agora %s", c.now())
                    .isEqualTo(ZonedDateTime.parse(c.expected() + "[America/Sao_Paulo]").toInstant());
        }
        // o mesmo instante visto em UTC (22h de Sao Paulo = 01h UTC do dia seguinte): o dia que vale e o de Sao Paulo
        Instant utcNight = ZonedDateTime.parse("2026-10-18T01:00:00Z").toInstant();
        var scheduler = new PaymentReminderScheduler(paymentService, push, Clock.fixed(utcNight, SP));
        assertThat(scheduler.nextFire())
                .isEqualTo(ZonedDateTime.parse("2026-10-18T10:00:00-03:00[America/Sao_Paulo]").toInstant());
    }

    @Test
    @DisplayName("relogio fixo no passado do relogio real: armar o proximo disparo nao roda nada em segundo plano (era o teste que falhava depois das 10h de 18/10)")
    void fixedClockNeverFiresInTheBackground() throws Exception {
        when(paymentService.remindersFinishedBefore(any())).thenReturn(List.of());
        for (String now : List.of("2026-10-17T10:00:00-03:00", "2020-01-01T09:00:00-03:00", "2040-06-01T23:00:00-03:00")) {
            var scheduler = at(now);
            scheduler.init();
            try {
                scheduler.onBoot();
                Thread.sleep(150);
            } finally {
                scheduler.shutdown();
            }
        }
        org.mockito.Mockito.verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("fire() manda e rearma; falha no ciclo nao derruba o timer")
    void fire() {
        var scheduler = at("2026-10-17T10:00:00-03:00");
        scheduler.init();
        try {
            when(paymentService.remindersFinishedBefore(any())).thenThrow(new IllegalStateException("banco fora"));
            scheduler.fire(); // nao propaga
            org.mockito.Mockito.doReturn(List.of()).when(paymentService).remindersFinishedBefore(any());
            scheduler.fire();
            scheduler.onBoot();
        } finally {
            scheduler.shutdown();
        }
    }
}
