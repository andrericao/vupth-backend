package com.ospaulos.vupth.domain.attendance.service;

import com.ospaulos.vupth.api.websocket.dto.QueueNotification;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import com.ospaulos.vupth.infrastructure.websocket.QueueWebSocketPublisher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Gatilho de promocao das intencoes de atendimento (backlog #4, fatia 3) — <b>EVENT-DRIVEN, nao polling</b>.
 *
 * <p>Uma intencao (status {@code INTENTION}) vive FORA da fila viva ate faltar ~{@value #LEAD_MINUTES}min pro
 * seu {@code desiredTime}; ai e promovida a {@code SCHEDULED} e entra na fila. Em vez de varrer o banco de tempos
 * em tempos, mantem-se <b>um unico timer</b> agendado para {@code cabeca.desiredTime - 15min}, onde a "cabeca" e a
 * intencao de menor {@code desiredTime} em todo o sistema. Fora dos disparos, custo ~zero.</p>
 *
 * <p><b>Fonte da verdade = a query</b> (o timer e so um palpite). No disparo o handler NAO promove cego: re-consulta
 * a cabeca ({@link AttendanceRepository#findEarliestIntention()}) e re-checa se ja esta na hora —
 * <b>loop de drenagem</b> promove todas as vencidas (cobre rajadas apos ociosidade) e reagenda pro proximo horario.</p>
 *
 * <p>Todo create/edit/cancel de intencao chama {@link #reschedule()} (pela camada web, pos-commit): a cabeca e o
 * gatilho se corrigem na hora — inclusive "mudou pra mais cedo" (nao fica esperando um despertador atrasado) e
 * "mudou pra mais tarde"/"cancelou" (a re-query no disparo simplesmente nao a promove). No boot, {@link #onBoot()}
 * reconstroi o timer a partir do banco (senao perderia o despertador da cabeca apos um restart do Render).</p>
 *
 * <p>Usa um {@link ThreadPoolTaskScheduler} <b>proprio</b> (instanciado aqui, nao um bean compartilhado) para nao
 * depender de {@code @EnableScheduling} — que esta deliberadamente ausente no projeto (a decisao "NAO auto-fechar"
 * deixa os {@code @Scheduled} dormentes; nao os quero acordar). Este scheduler e independente daquilo.</p>
 */
@Slf4j
@Component
public class IntentionPromotionScheduler {

    /** Antecedencia da promocao: a intencao vira fila quando falta este tanto pro {@code desiredTime}. */
    static final long LEAD_MINUTES = 15;

    private final AttendanceRepository attendanceRepository;
    private final AttendanceService attendanceService;
    private final QueueWebSocketPublisher queuePublisher;
    private final PushNotificationPublisher pushPublisher;

    /**
     * Arma o timer no boot ({@link #onBoot()}). Default {@code true} (producao). Desligado no profile de
     * teste ({@code vupth.intention-promotion.auto-arm=false}) para NAO subir um despertador de wall-clock
     * em cada contexto de integracao — os testes exercitam {@code promoteIntention}/{@code runPromotionCycle}
     * diretamente, sem depender de temporizacao real.
     */
    @org.springframework.beans.factory.annotation.Value("${vupth.intention-promotion.auto-arm:true}")
    private boolean autoArm = true;

    private ThreadPoolTaskScheduler taskScheduler;
    private ScheduledFuture<?> pending;

    /** Trava (reentrante) no lugar de synchronized: com threads virtuais, I/O dentro de um monitor prende a portadora. */
    private final ReentrantLock lock = new ReentrantLock();

    public IntentionPromotionScheduler(AttendanceRepository attendanceRepository,
                                       AttendanceService attendanceService,
                                       QueueWebSocketPublisher queuePublisher,
                                       PushNotificationPublisher pushPublisher) {
        this.attendanceRepository = attendanceRepository;
        this.attendanceService = attendanceService;
        this.queuePublisher = queuePublisher;
        this.pushPublisher = pushPublisher;
    }

    @PostConstruct
    void init() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("intention-promo-");
        scheduler.setRemoveOnCancelPolicy(true);
        // DAEMON: a thread do timer NUNCA pode segurar o encerramento da JVM. Sem isto, cada contexto de
        // integracao cacheado deixa uma thread nao-daemon viva e o fork do surefire (reuseForks=false) nao
        // encerra -> a suite trava ate o timeout do CI. Em producao o container tem sua propria thread
        // nao-daemon; o graceful shutdown continua chamando @PreDestroy.
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

    /** No boot/restart, reconstroi o timer da cabeca a partir do banco (senao perde o despertador). */
    @EventListener(ApplicationReadyEvent.class)
    public void onBoot() {
        if (!autoArm) {
            log.info("[IntentionPromo] boot — auto-arm desligado (profile de teste); timer nao sera armado");
            return;
        }
        log.info("[IntentionPromo] boot — reconstruindo timer de promocao a partir do banco");
        reschedule();
    }

    /**
     * (Re)agenda o gatilho para {@code cabeca.desiredTime - 15min}. Idempotente: cancela o timer anterior e
     * recalcula a cabeca. Se nao ha intencao, fica sem timer. Se a cabeca ja esta vencida (horario proximo ou
     * ja passou), dispara "ja" (em 1s). Chamado pos-commit de create/edit/cancel e no boot.
     */
    public void reschedule() {
        lock.lock();
        try {
            rescheduleLocked();
        } finally {
            lock.unlock();
        }
    }

    private void rescheduleLocked() {
        cancelPending();
        Optional<Attendance> head = attendanceRepository.findEarliestIntention();
        if (head.isEmpty() || head.get().getDesiredTime() == null) {
            log.info("[IntentionPromo] reschedule — nenhuma intencao pendente; timer ocioso");
            return;
        }
        Instant fireAt = fireInstantFor(head.get().getDesiredTime());
        pending = taskScheduler.schedule(this::runPromotionCycle, fireAt);
        log.info("[IntentionPromo] reschedule — cabeca desiredTime={} -> disparo em {}",
                head.get().getDesiredTime(), fireAt);
    }

    /**
     * Loop de drenagem: promove todas as intencoes ja vencidas ({@code now >= desiredTime - 15min}) em cadeia
     * (cobre rajadas apos ociosidade), reconsultando a cabeca a cada passo, e reagenda pro proximo horario.
     * Executado na thread do scheduler. Sincronizado com {@link #reschedule()} para nao correr com um create/edit.
     */
    void runPromotionCycle() {
        lock.lock();
        try {
            runPromotionCycleLocked();
        } finally {
            lock.unlock();
        }
    }

    private void runPromotionCycleLocked() {
        pending = null;
        while (true) {
            Optional<Attendance> headOpt = attendanceRepository.findEarliestIntention();
            if (headOpt.isEmpty() || headOpt.get().getDesiredTime() == null) {
                log.info("[IntentionPromo] ciclo — sem mais intencoes; timer ocioso");
                return;
            }
            Attendance head = headOpt.get();
            if (!isDue(head.getDesiredTime())) {
                // A cabeca ainda nao venceu (ex.: foi adiada) — reagenda e para.
                pending = taskScheduler.schedule(this::runPromotionCycle, fireInstantFor(head.getDesiredTime()));
                log.info("[IntentionPromo] ciclo — cabeca ainda nao venceu; reagenda desiredTime={}",
                        head.getDesiredTime());
                return;
            }
            try {
                attendanceService.promoteIntention(head.getId()).ifPresent(this::notifyPromotion);
            } catch (Exception e) {
                // Nao deixa uma linha problematica travar o loop nem o proprio scheduler: loga e reagenda
                // um retry curto (a re-query no proximo disparo reavalia a mesma cabeca).
                log.error("[IntentionPromo] ciclo — falha ao promover intencao {}: {}",
                        head.getId(), e.getMessage(), e);
                pending = taskScheduler.schedule(this::runPromotionCycle, Instant.now().plusSeconds(60));
                return;
            }
            // Promovida (ou corrida no-op) -> a cabeca avancou; re-consulta e continua drenando.
        }
    }

    /** Notifica o cliente promovido (WS pessoal + push) e rebroadcasta a fila. Best-effort. */
    private void notifyPromotion(AttendanceService.PromotionResult result) {
        queuePublisher.broadcastQueue(result.establishmentId());
        if (result.clientId() == null) {
            return; // convidado nao tem conta/token — nada a notificar
        }
        queuePublisher.notifyClient(result.clientId(), QueueNotification.of(
                "ATTENDANCE_PROMOTED", result.attendanceId(), result.establishmentId(),
                "Seu horario esta chegando — voce entrou na fila."));
        pushPublisher.notifyUser(result.clientId(), "Você entrou na fila",
                "Seu horário está chegando. Você já está na fila.",
                Map.of("type", "ATTENDANCE_PROMOTED",
                        "attendanceId", String.valueOf(result.attendanceId()),
                        "establishmentId", String.valueOf(result.establishmentId())));
    }

    /** Instante de disparo = {@code desiredTime - 15min}, com piso em "agora + 1s" (cabeca ja vencida). */
    private Instant fireInstantFor(OffsetDateTime desiredTime) {
        Instant fireAt = desiredTime.minusMinutes(LEAD_MINUTES).toInstant();
        Instant floor = Instant.now().plusSeconds(1);
        return fireAt.isBefore(floor) ? floor : fireAt;
    }

    /** A intencao ja pode ser promovida? ({@code now >= desiredTime - 15min}). */
    private boolean isDue(OffsetDateTime desiredTime) {
        return !OffsetDateTime.now().isBefore(desiredTime.minusMinutes(LEAD_MINUTES));
    }

    private void cancelPending() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }
}
