package com.ospaulos.vupth.domain.openwatch.service;

import com.ospaulos.vupth.domain.attendance.event.AttendanceStartedEvent;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.openwatch.exception.OpenWatchException;
import com.ospaulos.vupth.domain.openwatch.model.OpenWatch;
import com.ospaulos.vupth.domain.openwatch.model.OpenWatchKind;
import com.ospaulos.vupth.domain.openwatch.repository.OpenWatchRepository;
import com.ospaulos.vupth.domain.professional.event.WorkStatusChangedEvent;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * avisar-quando-abrir (BAK-8, DEC-160). O cliente logado pede, para uma loja ou um profissional, so no dia:
 * <ul>
 *   <li>{@link OpenWatchKind#NOTIFY_OPEN}: push "&lt;Profissional&gt; comecou a atender" quando ele entra em expediente
 *       (sem profissional no pedido, o primeiro da loja que entrar);</li>
 *   <li>{@link OpenWatchKind#QUEUE_MOVING}: push quando a fila comeca a andar (o primeiro atendimento iniciado), mesmo
 *       para quem esta longe na fila.</li>
 * </ul>
 * Tudo e opt-in: nenhum aviso sem o pedido. Dispara uma vez e expira a meia-noite (as consultas olham so o dia de hoje).
 * O envio e best-effort, depois do commit de quem mudou o estado, numa transacao propria: um push que falha nunca
 * desfaz o expediente nem o atendimento.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenWatchService {

    /** Teto de pedidos por usuario por dia (anti-abuso). */
    public static final int MAX_PER_DAY = 20;

    private final OpenWatchRepository repository;
    private final EstablishmentRepository establishmentRepository;
    private final ProfessionalRepository professionalRepository;
    private final AttendanceRepository attendanceRepository;
    private final PushNotificationPublisher pushPublisher;

    /** O pedido e se ele foi criado agora ({@code false} = ja existia hoje). */
    public record Subscription(OpenWatch watch, boolean created) {
    }

    /** Cria o pedido do dia; repetir o mesmo pedido devolve o que ja existe (idempotente). */
    @Transactional
    public Subscription subscribe(UUID userId, UUID establishmentId, UUID professionalId, OpenWatchKind kind) {
        establishmentRepository.findById(establishmentId).orElseThrow(OpenWatchException::establishmentNotFound);
        if (professionalId != null) {
            Professional professional = professionalRepository.findById(professionalId)
                    .orElseThrow(OpenWatchException::professionalNotInEstablishment);
            if (professional.getEstablishment() == null
                    || !establishmentId.equals(professional.getEstablishment().getId())) {
                throw OpenWatchException.professionalNotInEstablishment();
            }
        }
        LocalDate today = BusinessClock.today();
        List<OpenWatch> mine = repository.findByUserIdAndDayOrderByCreatedAtAsc(userId, today);
        for (OpenWatch watch : mine) {
            if (watch.getEstablishmentId().equals(establishmentId) && watch.getKind() == kind
                    && java.util.Objects.equals(watch.getProfessionalId(), professionalId)) {
                return new Subscription(watch, false);
            }
        }
        if (mine.size() >= MAX_PER_DAY) {
            throw OpenWatchException.limit(MAX_PER_DAY);
        }
        OpenWatch created = repository.save(new OpenWatch(userId, establishmentId, professionalId, kind, today));
        log.info("[OpenWatch] subscribe — user={} establishment={} professional={} kind={}",
                userId, establishmentId, professionalId, kind);
        return new Subscription(created, true);
    }

    /** Os pedidos de hoje do usuario (os ja disparados vem com fired_at). */
    @Transactional(readOnly = true)
    public List<OpenWatch> listToday(UUID userId) {
        return repository.findByUserIdAndDayOrderByCreatedAtAsc(userId, BusinessClock.today());
    }

    /** Cancela um pedido do proprio usuario; de outro usuario e 404 (nao revela que existe). */
    @Transactional
    public void cancel(UUID userId, UUID watchId) {
        OpenWatch watch = repository.findById(watchId)
                .filter(w -> w.getUserId().equals(userId))
                .orElseThrow(OpenWatchException::notFound);
        repository.delete(watch);
    }

    // ─────────────────────────────────────────── disparos

    @TransactionalEventListener(fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onWorkStatusChanged(WorkStatusChangedEvent event) {
        if (!event.startsShift() || event.establishmentId() == null) {
            return;
        }
        try {
            fire(OpenWatchKind.NOTIFY_OPEN, event.establishmentId(), event.professionalId());
        } catch (Exception e) {
            log.warn("[OpenWatch] aviso de abertura falhou — professional={}: {}", event.professionalId(), e.getMessage());
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAttendanceStarted(AttendanceStartedEvent event) {
        try {
            Attendance attendance = attendanceRepository.findByIdEager(event.attendanceId()).orElse(null);
            if (attendance == null || attendance.getEstablishment() == null || attendance.getProfessional() == null) {
                return;
            }
            fire(OpenWatchKind.QUEUE_MOVING, attendance.getEstablishment().getId(), attendance.getProfessional().getId());
        } catch (Exception e) {
            log.warn("[OpenWatch] aviso de fila andando falhou — attendance={}: {}", event.attendanceId(), e.getMessage());
        }
    }

    /** Dispara os pedidos de hoje que cobrem este profissional; devolve quantos. */
    int fire(OpenWatchKind kind, UUID establishmentId, UUID professionalId) {
        List<OpenWatch> pending = repository.findByEstablishmentIdAndKindAndDayAndFiredAtIsNull(
                establishmentId, kind, BusinessClock.today()).stream()
                .filter(w -> w.covers(professionalId))
                .toList();
        if (pending.isEmpty()) {
            return 0;
        }
        String professionalName = professionalRepository.findByIdWithBaseUser(professionalId)
                .map(Professional::getName).orElse("O profissional");
        String establishmentName = establishmentRepository.findById(establishmentId)
                .map(Establishment::getName).orElse("a loja");
        String title = kind == OpenWatchKind.NOTIFY_OPEN
                ? professionalName + " começou a atender"
                : "A fila começou a andar";
        String body = kind == OpenWatchKind.NOTIFY_OPEN
                ? establishmentName + " está atendendo agora. Toque para ver a fila."
                : professionalName + " iniciou o primeiro atendimento do dia em " + establishmentName + ".";
        Map<String, Object> data = Map.of(
                "type", kind == OpenWatchKind.NOTIFY_OPEN ? "OPEN_WATCH_OPENED" : "OPEN_WATCH_QUEUE_MOVING",
                "establishment_id", establishmentId.toString(),
                "professional_id", professionalId.toString());
        for (OpenWatch watch : pending) {
            pushPublisher.notifyUser(watch.getUserId(), title, body, data);
            watch.markFired();
            repository.save(watch);
        }
        log.info("[OpenWatch] {} disparado — establishment={} professional={} avisos={}",
                kind, establishmentId, professionalId, pending.size());
        return pending.size();
    }
}
