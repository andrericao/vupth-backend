package com.ospaulos.vupth.domain.professional.service;

import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.valueObject.OperationMode;
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

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;

/**
 * Aviso da vespera da agenda (agenda-aberta-semana, DEC-117/130): todo domingo as 10h (Sao Paulo), o profissional em modo agenda
 * que nao abriu nenhum dia da semana que comeca na segunda recebe o push {@code AGENDA_NOT_OPEN}, que leva ao modal de abrir.
 * So conta como aberto o dia que ELE abriu (um a um ou por "repetir a padrao"): a semana cadastrada nas configuracoes nao conta,
 * porque abrir e ato do profissional. Um por semana por profissional (o proprio disparo semanal). Timer proprio, como o do
 * lembrete de pagamento, e a {@link ClusterLock} para so uma instancia mandar.
 */
@Slf4j
@Component
public class AgendaEveReminderScheduler {

    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    static final LocalTime SEND_AT = LocalTime.of(10, 0);

    private final ProfessionalRepository professionalRepository;
    private final PushNotificationPublisher pushPublisher;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Value("${vupth.agenda-eve.auto-arm:true}")
    private boolean autoArm = true;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ClusterLock clusterLock;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SentryCron sentryCron;

    /** As horas do profissional sao colecoes LAZY: o ciclo roda numa transacao. Sem ela (teste de unidade), le direto. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.transaction.support.TransactionTemplate transaction;

    private ThreadPoolTaskScheduler taskScheduler;

    @org.springframework.beans.factory.annotation.Autowired
    public AgendaEveReminderScheduler(ProfessionalRepository professionalRepository, PushNotificationPublisher pushPublisher) {
        this(professionalRepository, pushPublisher, Clock.system(ZONE));
    }

    AgendaEveReminderScheduler(ProfessionalRepository professionalRepository, PushNotificationPublisher pushPublisher, Clock clock) {
        this.professionalRepository = professionalRepository;
        this.pushPublisher = pushPublisher;
        this.clock = clock;
    }

    @PostConstruct
    void init() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("agenda-eve-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setDaemon(true);
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
            log.info("[AgendaEve] boot — auto-arm desligado (profile de teste)");
            return;
        }
        scheduleNext();
    }

    /** O proximo disparo: o domingo as 10h (hoje, se ainda nao passou). */
    Instant nextFire() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime sunday = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).with(SEND_AT);
        return (sunday.isAfter(now) ? sunday : sunday.plusWeeks(1)).toInstant();
    }

    private void scheduleNext() {
        Instant at = nextFire();
        taskScheduler.schedule(this::fire, at);
        log.info("[AgendaEve] proximo aviso em {}", at);
    }

    void fire() {
        try {
            if (clusterLock == null) {
                monitored();
            } else {
                clusterLock.runExclusive("agenda-eve", this::monitored);
            }
        } catch (RuntimeException e) {
            log.error("[AgendaEve] falha no ciclo: {}", e.getMessage(), e);
        } finally {
            scheduleNext();
        }
    }

    private void monitored() {
        if (sentryCron == null) {
            sendReminders();
            return;
        }
        sentryCron.run("agenda-eve", io.sentry.MonitorSchedule.crontab("0 10 * * 0"), ZONE.getId(), 30, 10, this::sendReminders);
    }

    /** Manda o aviso a cada profissional em modo agenda sem nenhum dia aberto por ele na semana seguinte; devolve quantos. */
    int sendReminders() {
        if (transaction == null) {
            return doSendReminders();
        }
        Integer sent = transaction.execute(status -> doSendReminders());
        return sent == null ? 0 : sent;
    }

    private int doSendReminders() {
        LocalDate monday = LocalDate.now(clock).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        LocalDate sunday = monday.plusDays(6);
        int sent = 0;
        for (Professional p : professionalRepository.findActiveProfessionals()) {
            if (p.getOperationMode() != OperationMode.AGENDA_ONLY || p.getEstablishment() == null || openedAnyDay(p, monday, sunday)) {
                continue;
            }
            pushPublisher.notifyUser(p.getId(), "Agenda da semana que vem",
                    "Sua agenda da semana que vem está vazia. Abra os dias.",
                    Map.of("type", "AGENDA_NOT_OPEN", "weekStart", monday.toString()));
            sent++;
        }
        log.info("[AgendaEve] {} aviso(s) enviado(s) para a semana de {}", sent, monday);
        return sent;
    }

    private static boolean openedAnyDay(Professional p, LocalDate from, LocalDate to) {
        return p.getWorkingHours().exceptionsFrom(from).entrySet().stream()
                .anyMatch(e -> !e.getKey().isAfter(to) && !e.getValue().closed());
    }
}
