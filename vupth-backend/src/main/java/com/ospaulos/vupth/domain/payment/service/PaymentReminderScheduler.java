package com.ospaulos.vupth.domain.payment.service;

import com.ospaulos.vupth.domain.shared.Brl;
import com.ospaulos.vupth.infrastructure.config.ClusterLock;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import com.ospaulos.vupth.infrastructure.sentry.SentryCron;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

/**
 * Lembrete do pagamento pendente (push-pagamento-pendente, DEC-78): todo dia as 10h (Sao Paulo) o cliente com
 * atendimento finalizado em dia anterior e ainda PENDENTE recebe o {@code PAYMENT_PENDING} (botao Pagar). Para
 * sozinho quando paga: a consulta so traz o que segue pendente. Um timer proprio, como o da promocao das
 * intencoes (sem {@code @EnableScheduling}), e a {@link ClusterLock} para so uma instancia mandar.
 */
@Slf4j
@Component
public class PaymentReminderScheduler {

    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    static final LocalTime SEND_AT = LocalTime.of(10, 0);

    private final PaymentService paymentService;
    private final PushNotificationPublisher pushPublisher;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Value("${vupth.payment-reminder.auto-arm:true}")
    private boolean autoArm = true;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ClusterLock clusterLock;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SentryCron sentryCron;

    private ThreadPoolTaskScheduler taskScheduler;

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentReminderScheduler(PaymentService paymentService, PushNotificationPublisher pushPublisher) {
        this(paymentService, pushPublisher, Clock.system(ZONE));
    }

    PaymentReminderScheduler(PaymentService paymentService, PushNotificationPublisher pushPublisher, Clock clock) {
        this.paymentService = paymentService;
        this.pushPublisher = pushPublisher;
        this.clock = clock;
    }

    @PostConstruct
    void init() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("payment-reminder-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setDaemon(true); // nunca segura o encerramento da JVM (ver IntentionPromotionScheduler)
        // o atraso do proximo disparo conta no MESMO relogio do nextFire (producao: o do sistema). Sem isto, com um
        // relogio fixo no passado o disparo caia no passado do relogio real, rodava na hora e se rearmava sem parar.
        scheduler.setClock(clock);
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
            log.info("[PaymentReminder] boot — auto-arm desligado (profile de teste)");
            return;
        }
        scheduleNext();
    }

    /** O proximo disparo: as 10h de hoje, se ainda nao passaram, ou as de amanha. */
    Instant nextFire() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime today = now.with(SEND_AT);
        return (today.isAfter(now) ? today : today.plusDays(1)).toInstant();
    }

    private void scheduleNext() {
        Instant at = nextFire();
        taskScheduler.schedule(this::fire, at);
        log.info("[PaymentReminder] proximo lembrete em {}", at);
    }

    void fire() {
        try {
            if (clusterLock == null) {
                monitored();
            } else {
                clusterLock.runExclusive("payment-reminder", this::monitored);
            }
        } catch (RuntimeException e) {
            log.error("[PaymentReminder] falha no ciclo: {}", e.getMessage(), e);
        } finally {
            scheduleNext();
        }
    }

    /** sentry-monitor-de-cron: o ciclo das 10h (Sao Paulo) manda check-in; se deixar de rodar ou falhar, o Sentry avisa. */
    private void monitored() {
        if (sentryCron == null) {
            sendReminders();
            return;
        }
        sentryCron.run("payment-reminder", io.sentry.MonitorSchedule.crontab("0 10 * * *"), ZONE.getId(), 30, 10,
                this::sendReminders);
    }

    /** Manda o lembrete de cada pagamento pendente de atendimento finalizado antes de hoje; devolve quantos. */
    int sendReminders() {
        LocalDate today = LocalDate.now(clock);
        var due = paymentService.remindersFinishedBefore(today.atStartOfDay(ZONE).toOffsetDateTime());
        for (var r : due) {
            pushPublisher.notifyUser(r.clientId(), "Pagamento pendente", pendingText(r.amount()),
                    Map.of("type", "PAYMENT_PENDING",
                            "attendanceId", r.attendanceId().toString(),
                            "establishmentId", String.valueOf(r.establishmentId()),
                            "amount", r.amount().toPlainString()));
        }
        log.info("[PaymentReminder] {} lembrete(s) enviado(s)", due.size());
        return due.size();
    }

    /** "Atendimento concluído. Falta o pagamento de R$ 12,90." */
    public static String pendingText(BigDecimal amount) {
        return "Atendimento concluído. Falta o pagamento de " + Brl.of(amount) + ".";
    }
}
