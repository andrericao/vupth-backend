package com.ospaulos.vupth.domain.attendance.cut.service;

import com.ospaulos.vupth.infrastructure.config.ClusterLock;
import com.ospaulos.vupth.domain.attendance.cut.CutRequestStatus;
import com.ospaulos.vupth.domain.attendance.cut.model.CutRequest;
import com.ospaulos.vupth.domain.attendance.cut.repository.CutRequestRepository;
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
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Auto-nega dos pedidos de corte (BUG5 C2, fatia 3) — <b>EVENT-DRIVEN, nao polling</b>, mesmo padrao do
 * {@link com.ospaulos.vupth.domain.attendance.service.IntentionPromotionScheduler}.
 *
 * <p>Um {@code CutRequest} PENDING vale 5min; passou {@code expiresAt} sem o profissional responder, vira
 * {@code EXPIRED} e o cliente e avisado. Em vez de varrer o banco de tempos em tempos, mantem-se <b>um unico
 * timer</b> agendado para o {@code expiresAt} da "cabeca" (o PENDING que vence primeiro). Fora dos disparos,
 * custo ~zero. 100% interno — sem endpoint REST.</p>
 *
 * <p><b>Fonte da verdade = a query.</b> No disparo o handler expira TODOS os vencidos de uma vez
 * ({@link CutRequestService#expireDueCutRequests()}) e reagenda para a proxima cabeca. Todo create/accept/refuse
 * chama {@link #reschedule()} (pela camada web, pos-commit) para corrigir o gatilho na hora. No boot,
 * {@link #onBoot()} reconstroi o timer a partir do banco (senao perderia o despertador apos um restart do Render).</p>
 *
 * <p>Usa um {@link ThreadPoolTaskScheduler} <b>proprio</b> com thread <b>daemon</b> — mesma justificativa do
 * scheduler de intencao: nao depende de {@code @EnableScheduling} (ausente no projeto) e a thread nunca segura
 * o encerramento da JVM (critico sob {@code reuseForks} no CI).</p>
 */
@Slf4j
@Component
public class CutRequestExpiryScheduler {

    private final CutRequestRepository cutRequestRepository;
    private final CutRequestService cutRequestService;

    /**
     * Arma o timer no boot ({@link #onBoot()}). Default {@code true} (producao). Desligado no profile de
     * teste ({@code vupth.cut-request.auto-arm=false}) para NAO subir um despertador de wall-clock em cada
     * contexto de integracao — os testes exercitam {@code expireDueCutRequests}/{@code runExpiryCycle}
     * diretamente, sem depender de temporizacao real.
     */
    @Value("${vupth.cut-request.auto-arm:true}")
    private boolean autoArm = true;

    private ThreadPoolTaskScheduler taskScheduler;
    private ScheduledFuture<?> pending;

    /** Trava (reentrante) no lugar de synchronized: com threads virtuais, I/O dentro de um monitor prende a portadora. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Trava entre instancias. Opcional so para os testes unitarios, que montam o scheduler na mao. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ClusterLock clusterLock;

    public CutRequestExpiryScheduler(CutRequestRepository cutRequestRepository,
                                     CutRequestService cutRequestService) {
        this.cutRequestRepository = cutRequestRepository;
        this.cutRequestService = cutRequestService;
    }

    @PostConstruct
    void init() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("cut-expiry-");
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

    /** No boot/restart, reconstroi o timer da cabeca a partir do banco (senao perde o despertador). */
    @EventListener(ApplicationReadyEvent.class)
    public void onBoot() {
        if (!autoArm) {
            log.info("[CutExpiry] boot — auto-arm desligado (profile de teste); timer nao sera armado");
            return;
        }
        log.info("[CutExpiry] boot — reconstruindo timer de expiracao a partir do banco");
        reschedule();
    }

    /**
     * (Re)agenda o gatilho para {@code cabeca.expiresAt}. Idempotente: cancela o timer anterior e recalcula
     * a cabeca. Sem PENDING -> fica ocioso. Se a cabeca ja venceu, dispara "ja" (em 1s). Chamado pos-commit de
     * create/accept/refuse e no boot.
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
        Optional<CutRequest> head = cutRequestRepository.findFirstByStatusOrderByExpiresAtAsc(CutRequestStatus.PENDING);
        if (head.isEmpty()) {
            log.info("[CutExpiry] reschedule — nenhum pedido PENDING; timer ocioso");
            return;
        }
        Instant fireAt = fireInstantFor(head.get().getExpiresAt());
        pending = taskScheduler.schedule(this::runExpiryCycle, fireAt);
        log.info("[CutExpiry] reschedule — cabeca expiresAt={} -> disparo em {}", head.get().getExpiresAt(), fireAt);
    }

    /**
     * Expira todos os PENDING ja vencidos e reagenda para a proxima cabeca. Executado na thread do scheduler.
     * Sincronizado com {@link #reschedule()} para nao correr com um create/accept/refuse.
     */
    void runExpiryCycle() {
        lock.lock();
        try {
            if (clusterLock == null) {
                runExpiryCycleLocked();
                return;
            }
            if (clusterLock.runExclusive("cut-request-expiry", this::runExpiryCycleLocked)) {
                return;
            }
            // Outra instancia esta rodando este ciclo: ela processa o que venceu; aqui so se tenta de novo.
            log.info("[CutExpiry] ciclo — outra instancia tem a trava; nova tentativa em 30s");
            pending = taskScheduler.schedule(this::runExpiryCycle, Instant.now().plusSeconds(30));
        } finally {
            lock.unlock();
        }
    }

    private void runExpiryCycleLocked() {
        pending = null;
        try {
            int expired = cutRequestService.expireDueCutRequests();
            log.info("[CutExpiry] ciclo — {} pedido(s) expirado(s)", expired);
        } catch (Exception e) {
            // Nao deixa uma falha travar o scheduler: loga e reagenda um retry curto (a re-query reavalia).
            log.error("[CutExpiry] ciclo — falha ao expirar pedidos: {}", e.getMessage(), e);
            pending = taskScheduler.schedule(this::runExpiryCycle, Instant.now().plusSeconds(60));
            return;
        }
        reschedule(); // reentrante (mesma thread/monitor): aponta o timer para a proxima cabeca PENDING
    }

    /** Instante de disparo = {@code expiresAt}, com piso em "agora + 1s" (cabeca ja vencida). */
    private Instant fireInstantFor(OffsetDateTime expiresAt) {
        Instant fireAt = expiresAt.toInstant();
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
