package com.ospaulos.vupth.infrastructure.push;

import com.ospaulos.vupth.domain.notification.model.PendingPushReceipt;
import com.ospaulos.vupth.domain.notification.repository.DeviceTokenRepository;
import com.ospaulos.vupth.domain.notification.repository.PendingPushReceiptRepository;
import com.ospaulos.vupth.infrastructure.config.ClusterLock;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Le os recibos da Expo (recibos-da-expo, 10/10; o "b" do push-conta-errada).
 *
 * <p>O ticket do envio so diz que a Expo aceitou. O recibo, ~15 min depois, diz se ela entregou a
 * FCM/APNs. A cada {@value #CYCLE_MINUTES} min este ciclo pega os tickets com mais de
 * {@value #RECEIPT_DELAY_MINUTES} min e:</p>
 * <ul>
 *   <li>recibo ok: loga que chegou a FCM/APNs e apaga a linha;</li>
 *   <li>recibo com {@code DeviceNotRegistered}: apaga o token (o app saiu do aparelho) e a linha;</li>
 *   <li>outro erro: loga o codigo e apaga a linha;</li>
 *   <li>sem recibo ainda: espera o proximo ciclo; com mais de {@value #RECEIPT_TTL_HOURS} h, a Expo ja
 *       descartou o recibo, entao a linha sai com um log.</li>
 * </ul>
 *
 * <p>Timer proprio (sem {@code @EnableScheduling}, como o {@link
 * com.ospaulos.vupth.domain.attendance.service.IntentionPromotionScheduler}) e a trava entre instancias
 * do {@link ClusterLock}. As linhas ficam no banco: o Render hiberna e o ciclo retoma no proximo boot.</p>
 */
@Slf4j
@Component
public class PushReceiptChecker {

    static final long CYCLE_MINUTES = 5;
    static final long RECEIPT_DELAY_MINUTES = 15;
    static final long RECEIPT_TTL_HOURS = 24;
    static final int PAGE_SIZE = 1000;

    private final PendingPushReceiptRepository pendingReceiptRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final PushSender pushSender;
    private final Clock clock;

    @Value("${vupth.push.receipts.auto-arm:true}")
    private boolean autoArm = true;

    /** Trava entre instancias. Opcional so para os testes unitarios. */
    @Autowired(required = false)
    private ClusterLock clusterLock;

    private ThreadPoolTaskScheduler taskScheduler;

    @Autowired
    public PushReceiptChecker(PendingPushReceiptRepository pendingReceiptRepository,
                              DeviceTokenRepository deviceTokenRepository,
                              PushSender pushSender) {
        this(pendingReceiptRepository, deviceTokenRepository, pushSender, Clock.systemUTC());
    }

    PushReceiptChecker(PendingPushReceiptRepository pendingReceiptRepository,
                       DeviceTokenRepository deviceTokenRepository,
                       PushSender pushSender,
                       Clock clock) {
        this.pendingReceiptRepository = pendingReceiptRepository;
        this.deviceTokenRepository = deviceTokenRepository;
        this.pushSender = pushSender;
        this.clock = clock;
    }

    @PostConstruct
    void init() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("push-receipts-");
        // DAEMON: o timer nunca segura o fim da JVM (ver IntentionPromotionScheduler).
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
            log.info("[PUSH] recibos — auto-arm desligado (profile de teste); ciclo nao sera armado");
            return;
        }
        taskScheduler.scheduleWithFixedDelay(this::runCycle, Duration.ofMinutes(CYCLE_MINUTES));
        log.info("[PUSH] recibos — ciclo armado a cada {} min", CYCLE_MINUTES);
    }

    /** Um ciclo, com a trava entre instancias. Nunca lanca: o timer nao pode morrer. */
    void runCycle() {
        try {
            if (clusterLock == null) {
                checkDueReceipts();
            } else {
                clusterLock.runExclusive("push-receipts", this::checkDueReceipts);
            }
        } catch (Exception e) {
            log.warn("[PUSH] recibos — falha no ciclo: {}", e.getMessage());
        }
    }

    /** Le os recibos dos tickets vencidos (ate {@value #PAGE_SIZE} por ciclo). Devolve quantos sairam. */
    int checkDueReceipts() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<PendingPushReceipt> due = pendingReceiptRepository.findBySentAtLessThanEqualOrderBySentAtAsc(
                now.minusMinutes(RECEIPT_DELAY_MINUTES), PageRequest.of(0, PAGE_SIZE));
        if (due.isEmpty()) {
            return 0;
        }
        Map<String, PushReceipt> receipts = pushSender.fetchReceipts(
                due.stream().map(PendingPushReceipt::getTicketId).toList());

        List<String> done = new ArrayList<>();
        int delivered = 0;
        int failed = 0;
        int expired = 0;
        OffsetDateTime ttl = now.minusHours(RECEIPT_TTL_HOURS);
        for (PendingPushReceipt pending : due) {
            PushReceipt receipt = receipts.get(pending.getTicketId());
            if (receipt == null) {
                if (pending.getSentAt().isBefore(ttl)) {
                    expired++;
                    done.add(pending.getTicketId());
                    log.info("[PUSH] recibo — sem recibo em {} h, descartado: user={} title='{}' token={}",
                            RECEIPT_TTL_HOURS, pending.getUserId(), pending.getTitle(),
                            PushTokenMask.mask(pending.getToken()));
                }
                continue;
            }
            done.add(pending.getTicketId());
            if (receipt.ok()) {
                delivered++;
                log.info("[PUSH] recibo — entregue a FCM/APNs: user={} title='{}' sent_at={}",
                        pending.getUserId(), pending.getTitle(), pending.getSentAt());
                continue;
            }
            failed++;
            log.warn("[PUSH] recibo — NAO entregue: user={} title='{}' token={} error={}: {}",
                    pending.getUserId(), pending.getTitle(), PushTokenMask.mask(pending.getToken()),
                    receipt.error(), receipt.message());
            if (receipt.isDeviceGone()) {
                removeDeadToken(pending);
            }
        }
        if (!done.isEmpty()) {
            pendingReceiptRepository.deleteByTicketIdIn(done);
        }
        log.info("[PUSH] recibos — {} lido(s): {} entregue(s), {} com erro, {} sem recibo em {} h; {} esperando",
                due.size(), delivered, failed, expired, RECEIPT_TTL_HOURS, due.size() - done.size());
        return done.size();
    }

    private void removeDeadToken(PendingPushReceipt pending) {
        try {
            deviceTokenRepository.deleteByToken(pending.getToken());
            log.info("[PUSH] device token removido pelo recibo (DeviceNotRegistered) — user={} token={}",
                    pending.getUserId(), PushTokenMask.mask(pending.getToken()));
        } catch (Exception e) {
            log.warn("[PUSH] falha ao remover device token morto {}: {}",
                    PushTokenMask.mask(pending.getToken()), e.getMessage());
        }
    }
}
