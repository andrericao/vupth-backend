package com.ospaulos.vupth.infrastructure.sentry;

import io.sentry.CheckIn;
import io.sentry.CheckInStatus;
import io.sentry.MonitorConfig;
import io.sentry.MonitorSchedule;
import io.sentry.Sentry;
import io.sentry.protocol.SentryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * sentry-monitor-de-cron: o Sentry Cron avisa quando um agendador deixa de rodar ou falha (como o cron das 6h que deu 503).
 * Cada ciclo manda um check-in "em andamento" e, no fim, "ok" ou "erro" com a duracao. O plano gratis tem poucos monitores,
 * entao so os de {@code vupth.sentry.cron-monitors} (separados por virgula) mandam check-in; os demais rodam como sempre.
 * Sem SENTRY_DSN o Sentry e no-op e nada sai. Sem dado pessoal: so o nome do monitor e a duracao.
 */
@Slf4j
@Component
public class SentryCron {

    private final Set<String> enabled;

    public SentryCron(@Value("${vupth.sentry.cron-monitors:payment-reminder}") String monitors) {
        this.enabled = Arrays.stream(monitors.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Roda {@code task} dentro de um check-in do monitor {@code slug}. A excecao da tarefa sobe de novo (o check-in sai como
     * erro); falha ao falar com o Sentry nunca derruba o ciclo.
     */
    public void run(String slug, MonitorSchedule schedule, String timezone, long marginMinutes, long maxRuntimeMinutes,
                    Runnable task) {
        if (!enabled.contains(slug)) {
            task.run();
            return;
        }
        long start = System.nanoTime();
        SentryId id = checkIn(null, slug, CheckInStatus.IN_PROGRESS, null, schedule, timezone, marginMinutes, maxRuntimeMinutes);
        try {
            task.run();
        } catch (RuntimeException | Error e) {
            checkIn(id, slug, CheckInStatus.ERROR, seconds(start), schedule, timezone, marginMinutes, maxRuntimeMinutes);
            throw e;
        }
        checkIn(id, slug, CheckInStatus.OK, seconds(start), schedule, timezone, marginMinutes, maxRuntimeMinutes);
    }

    private static double seconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }

    private static SentryId checkIn(SentryId id, String slug, CheckInStatus status, Double duration, MonitorSchedule schedule,
                                    String timezone, long marginMinutes, long maxRuntimeMinutes) {
        try {
            CheckIn checkIn = id == null ? new CheckIn(slug, status) : new CheckIn(id, slug, status);
            checkIn.setDuration(duration);
            MonitorConfig config = new MonitorConfig(schedule);
            config.setTimezone(timezone);
            config.setCheckinMargin(marginMinutes);
            config.setMaxRuntime(maxRuntimeMinutes);
            checkIn.setMonitorConfig(config);
            SentryId sent = Sentry.captureCheckIn(checkIn);
            return sent == null ? id : sent;
        } catch (RuntimeException e) {
            log.warn("[SentryCron] check-in de {} nao saiu: {}", slug, e.getMessage());
            return id;
        }
    }
}
