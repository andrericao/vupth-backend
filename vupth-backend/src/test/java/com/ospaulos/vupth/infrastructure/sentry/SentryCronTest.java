package com.ospaulos.vupth.infrastructure.sentry;

import io.sentry.CheckIn;
import io.sentry.MonitorSchedule;
import io.sentry.Sentry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

@DisplayName("sentry-monitor-de-cron: check-in do agendador, ok ou erro, so nos monitores ligados")
class SentryCronTest {

    private static final MonitorSchedule DAILY = MonitorSchedule.crontab("0 10 * * *");

    private List<CheckIn> run(SentryCron cron, Runnable task, boolean expectThrow) {
        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            // como o Sentry de verdade: devolve a id do proprio check-in
            sentry.when(() -> Sentry.captureCheckIn(any(CheckIn.class)))
                    .thenAnswer(call -> ((CheckIn) call.getArgument(0)).getCheckInId());
            if (expectThrow) {
                assertThatThrownBy(() -> cron.run("payment-reminder", DAILY, "America/Sao_Paulo", 30, 10, task))
                        .isInstanceOf(IllegalStateException.class);
            } else {
                cron.run("payment-reminder", DAILY, "America/Sao_Paulo", 30, 10, task);
            }
            ArgumentCaptor<CheckIn> captor = ArgumentCaptor.forClass(CheckIn.class);
            sentry.verify(() -> Sentry.captureCheckIn(captor.capture()), org.mockito.Mockito.atLeastOnce());
            return captor.getAllValues().stream().filter(java.util.Objects::nonNull).toList();
        }
    }

    @Test
    @DisplayName("ciclo que termina: em andamento e depois ok, com a mesma id, a duracao e o horario do monitor")
    void okCycle() {
        AtomicInteger ran = new AtomicInteger();

        List<CheckIn> sent = run(new SentryCron("payment-reminder"), ran::incrementAndGet, false);

        assertThat(ran).hasValue(1);
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).getStatus()).isEqualTo("in_progress");
        assertThat(sent.get(1).getStatus()).isEqualTo("ok");
        assertThat(sent.get(1).getCheckInId()).isEqualTo(sent.get(0).getCheckInId());
        assertThat(sent.get(1).getDuration()).isNotNull().isGreaterThanOrEqualTo(0.0);
        assertThat(sent.get(0).getMonitorConfig().getSchedule().getValue()).isEqualTo("0 10 * * *");
        assertThat(sent.get(0).getMonitorConfig().getTimezone()).isEqualTo("America/Sao_Paulo");
        assertThat(sent.get(0).getMonitorSlug()).isEqualTo("payment-reminder");
    }

    @Test
    @DisplayName("ciclo que falha: check-in de erro e a excecao sobe de novo")
    void failingCycle() {
        List<CheckIn> sent = run(new SentryCron("payment-reminder"), () -> {
            throw new IllegalStateException("quebrou");
        }, true);

        assertThat(sent).extracting(CheckIn::getStatus).containsExactly("in_progress", "error");
    }

    @Test
    @DisplayName("monitor desligado: a tarefa roda e nada vai ao Sentry")
    void disabledMonitor() {
        AtomicInteger ran = new AtomicInteger();
        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            new SentryCron("access-log-purge").run("payment-reminder", DAILY, "UTC", 1, 1, ran::incrementAndGet);

            assertThat(ran).hasValue(1);
            sentry.verify(() -> Sentry.captureCheckIn(any(CheckIn.class)), never());
        }
    }

    @Test
    @DisplayName("falha ao falar com o Sentry nao derruba o ciclo")
    void sentryDownDoesNotBreakTheCycle() {
        AtomicInteger ran = new AtomicInteger();
        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            sentry.when(() -> Sentry.captureCheckIn(any(CheckIn.class))).thenThrow(new RuntimeException("sem rede"));

            new SentryCron("payment-reminder, access-log-purge").run("payment-reminder", DAILY, "UTC", 1, 1, ran::incrementAndGet);

            assertThat(ran).hasValue(1);
        }
    }
}
