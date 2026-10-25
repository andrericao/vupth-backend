package com.ospaulos.vupth.domain.access.service;

import com.ospaulos.vupth.infrastructure.config.ClusterLock;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Apaga, uma vez por dia (e no boot), o registro de acesso com mais de 6 meses (registro-de-acesso).
 * Timer proprio, sem {@code @EnableScheduling} (como o {@code IntentionPromotionScheduler}), e a trava
 * entre instancias do {@link ClusterLock}. No Render que hiberna, o boot cobre o dia em que o timer nao
 * chegou a disparar.
 */
@Slf4j
@Component
public class AccessLogPurgeScheduler {

    static final Duration PERIOD = Duration.ofDays(1);

    private final AccessLogService accessLogService;

    @Value("${vupth.access-log.auto-arm:true}")
    private boolean autoArm = true;

    @Autowired(required = false)
    private ClusterLock clusterLock;

    @Autowired(required = false)
    private com.ospaulos.vupth.infrastructure.sentry.SentryCron sentryCron;

    private ThreadPoolTaskScheduler taskScheduler;

    public AccessLogPurgeScheduler(AccessLogService accessLogService) {
        this.accessLogService = accessLogService;
    }

    @PostConstruct
    void init() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("access-log-purge-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        this.taskScheduler = scheduler;
    }

    @PreDestroy
    void shutdown() {
        if (taskScheduler != null) {
            taskScheduler.shutdown();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onBoot() {
        if (!autoArm) {
            log.info("[Access] limpeza — auto-arm desligado (profile de teste)");
            return;
        }
        taskScheduler.scheduleAtFixedRate(this::runCycle, PERIOD);
        log.info("[Access] limpeza — ciclo armado a cada {}", PERIOD);
    }

    /** sentry-monitor-de-cron: a limpeza diaria manda check-in (se o monitor estiver ligado em vupth.sentry.cron-monitors). */
    private void monitored() {
        if (sentryCron == null) {
            accessLogService.purgeExpired();
            return;
        }
        sentryCron.run("access-log-purge", io.sentry.MonitorSchedule.interval(1, io.sentry.MonitorScheduleUnit.DAY), "UTC",
                120, 30, accessLogService::purgeExpired);
    }

    /** Um ciclo, com a trava entre instancias. Nunca lanca: o timer nao pode morrer. */
    void runCycle() {
        try {
            if (clusterLock == null) {
                monitored();
            } else {
                clusterLock.runExclusive("access-log-purge", this::monitored);
            }
        } catch (Exception e) {
            log.warn("[Access] limpeza — falha no ciclo: {}", e.getMessage());
        }
    }
}
