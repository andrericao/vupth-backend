package com.ospaulos.vupth.domain.establishment.service;

import com.ospaulos.vupth.api.websocket.dto.QueueNotification;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import com.ospaulos.vupth.infrastructure.websocket.QueueWebSocketPublisher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

/**
 * Auto-fechamento por horario (S1 fatia 5 / BUG1 — "a loja nao pode dormir aberta") — <b>EVENT-DRIVEN,
 * nao polling</b>, mesmo padrao de {@link com.ospaulos.vupth.domain.attendance.service.IntentionPromotionScheduler}
 * e {@link com.ospaulos.vupth.domain.attendance.cut.service.CutRequestExpiryScheduler}.
 *
 * <p>Mantem-se <b>um unico timer</b> agendado para o proximo instante de trabalho
 * ({@link AutoCloseService#nextEventAt()} = a menor carencia pendente ou o proximo {@code closing_time}
 * de uma loja com presenca). Fora dos disparos, custo ~zero. No disparo roda as duas fases do
 * {@link AutoCloseService} e notifica (pos-commit, best-effort):</p>
 * <ol>
 *   <li>{@link AutoCloseService#promptDueClosings()} — quem esta em expediente logo apos o fechamento
 *       tem a fila fechada e a carencia armada; o profissional recebe {@code CLOSING_PROMPT} (WS +
 *       push, com o {@code graceUntil} p/ o countdown) e os clientes na fila recebem {@code QUEUE_CLOSING}.</li>
 *   <li>{@link AutoCloseService#applyDueGraces()} — carencias vencidas viram auto-{@code OFF} (ou
 *       drenagem/auto-correcao); no auto-OFF rebroadcasta a fila (open_status derivado mudou).</li>
 * </ol>
 *
 * <p><b>Fonte da verdade = a query</b> (o timer e so um palpite). Toda mudanca que altera o proximo
 * evento — {@code PATCH /professional/me/status} e {@code .../accepting-new} — chama {@link #reschedule()}
 * (pela camada web, pos-commit). No boot, {@link #onBoot()} reconstroi o timer a partir do banco (senao
 * perderia o despertador apos um restart do Render).</p>
 *
 * <p>Usa um {@link ThreadPoolTaskScheduler} <b>proprio</b> com thread <b>daemon</b> — mesma justificativa
 * dos outros schedulers: nao depende de {@code @EnableScheduling} (ausente no projeto) e a thread nunca
 * segura o encerramento da JVM (critico sob {@code reuseForks} no CI).</p>
 */
@Slf4j
@Component
public class EstablishmentAutoCloseScheduler {

    private final AutoCloseService autoCloseService;
    private final QueueWebSocketPublisher queuePublisher;
    private final PushNotificationPublisher pushPublisher;

    /**
     * Arma o timer no boot ({@link #onBoot()}). Default {@code true} (producao). Desligado no profile de
     * teste ({@code vupth.auto-close.auto-arm=false}) para NAO subir um despertador de wall-clock em cada
     * contexto de integracao — os testes exercitam {@code promptDueClosings}/{@code applyDueGraces}/
     * {@code runCycle} diretamente, sem depender de temporizacao real.
     */
    @Value("${vupth.auto-close.auto-arm:true}")
    private boolean autoArm = true;

    private ThreadPoolTaskScheduler taskScheduler;
    private ScheduledFuture<?> pending;

    public EstablishmentAutoCloseScheduler(AutoCloseService autoCloseService,
                                           QueueWebSocketPublisher queuePublisher,
                                           PushNotificationPublisher pushPublisher) {
        this.autoCloseService = autoCloseService;
        this.queuePublisher = queuePublisher;
        this.pushPublisher = pushPublisher;
    }

    @PostConstruct
    void init() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("auto-close-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setDaemon(true); // ver justificativa no javadoc / IntentionPromotionScheduler
        scheduler.initialize();
        this.taskScheduler = scheduler;
    }

    @PreDestroy
    void shutdown() {
        if (taskScheduler != null) {
            taskScheduler.shutdown();
        }
    }

    /** No boot/restart, reconstroi o timer a partir do banco (senao perde o despertador). */
    @EventListener(ApplicationReadyEvent.class)
    public void onBoot() {
        if (!autoArm) {
            log.info("[AutoClose] boot — auto-arm desligado (profile de teste); timer nao sera armado");
            return;
        }
        log.info("[AutoClose] boot — reconstruindo timer de auto-fechamento a partir do banco");
        reschedule();
    }

    /**
     * (Re)agenda o gatilho para {@link AutoCloseService#nextEventAt()}. Idempotente: cancela o timer
     * anterior e recalcula o proximo evento. Sem evento -> fica ocioso. Se ja venceu, dispara "ja" (em
     * 1s). Chamado pos-commit de mudanca de status/accepting-new e no boot.
     */
    public synchronized void reschedule() {
        cancelPending();
        Optional<OffsetDateTime> next = autoCloseService.nextEventAt();
        if (next.isEmpty()) {
            log.info("[AutoClose] reschedule — nenhum evento armado; timer ocioso");
            return;
        }
        Instant fireAt = fireInstantFor(next.get());
        pending = taskScheduler.schedule(this::runCycle, fireAt);
        log.info("[AutoClose] reschedule — proximo evento={} -> disparo em {}", next.get(), fireAt);
    }

    /**
     * Roda as duas fases do auto-fechamento e reagenda para o proximo evento. Executado na thread do
     * scheduler. Sincronizado com {@link #reschedule()} para nao correr com um create/edit da camada web.
     */
    synchronized void runCycle() {
        pending = null;
        try {
            autoCloseService.promptDueClosings().forEach(this::notifyPrompt);
            autoCloseService.applyDueGraces().forEach(this::notifyGrace);
        } catch (Exception e) {
            // Nao deixa uma falha travar o scheduler: loga e reagenda um retry curto (a re-query reavalia).
            log.error("[AutoClose] ciclo — falha ao processar auto-fechamento: {}", e.getMessage(), e);
            pending = taskScheduler.schedule(this::runCycle, Instant.now().plusSeconds(60));
            return;
        }
        reschedule(); // reentrante (mesma thread/monitor): aponta o timer para o proximo evento
    }

    /**
     * Notifica o profissional (WS {@code CLOSING_PROMPT} + push, com o {@code graceUntil}) e cada
     * cliente na fila ({@code QUEUE_CLOSING}), e rebroadcasta a fila (a fila acabou de ser fechada).
     * Best-effort. O userId do profissional == professionalId (MapsId), destino do WS pessoal.
     */
    private void notifyPrompt(AutoCloseService.PromptedProfessional prompt) {
        queuePublisher.broadcastQueue(prompt.establishmentId());

        queuePublisher.notifyClient(prompt.professionalId(), QueueNotification.of(
                        "CLOSING_PROMPT", null, prompt.establishmentId(),
                        "O horario de fechamento passou — quer encerrar? Sem resposta, encerramos sozinho.")
                .withGraceUntil(prompt.graceUntil()));
        pushPublisher.notifyUser(prompt.professionalId(), "Hora de fechar?",
                "O horário de fechamento passou. Sem resposta em alguns minutos, encerramos sozinho.",
                Map.of("type", "CLOSING_PROMPT",
                        "establishmentId", String.valueOf(prompt.establishmentId()),
                        "graceUntil", String.valueOf(prompt.graceUntil())));

        for (AutoCloseService.WaitingClient waiting : prompt.waitingClients()) {
            queuePublisher.notifyClient(waiting.clientId(), QueueNotification.of(
                    "QUEUE_CLOSING", waiting.attendanceId(), prompt.establishmentId(),
                    "A fila foi encerrada para novas entradas — você ainda será atendido."));
            pushPublisher.notifyUser(waiting.clientId(), "Fila encerrada para novas entradas",
                    "Você continua na fila e ainda será atendido.",
                    Map.of("type", "QUEUE_CLOSING",
                            "attendanceId", String.valueOf(waiting.attendanceId()),
                            "establishmentId", String.valueOf(prompt.establishmentId())));
        }
    }

    /** No auto-OFF, o open_status derivado pode ter mudado -> rebroadcasta o snapshot da fila. */
    private void notifyGrace(AutoCloseService.GraceOutcome outcome) {
        if (outcome.autoOff()) {
            queuePublisher.broadcastQueue(outcome.establishmentId());
        }
    }

    /** Instante de disparo, com piso em "agora + 1s" (evento ja vencido). */
    private Instant fireInstantFor(OffsetDateTime eventAt) {
        Instant fireAt = eventAt.toInstant();
        Instant floor = Instant.now().plusSeconds(1);
        return fireAt.isBefore(floor) ? floor : fireAt;
    }

    private void cancelPending() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }
}
