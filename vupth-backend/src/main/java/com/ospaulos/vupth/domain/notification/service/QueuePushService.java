package com.ospaulos.vupth.domain.notification.service;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Push "voce e o proximo" (backlog #1). Ao mudar a fila de um establishment, avisa por push o cliente
 * que passou a ser o <b>primeiro</b> da fila de cada profissional (o proximo a ser atendido).
 *
 * <p>Deduplica pela flag {@code Attendance.nextNotified}: cada atendimento recebe o aviso <b>uma vez</b>
 * ao alcancar a frente — nao reenvia a cada nova mutacao enquanto continuar na frente. Convidados (sem
 * conta) nao recebem. E chamado pos-mutacao junto do broadcast WS da fila; best-effort.</p>
 *
 * <p>Interruptor "expor a posicao na fila" (decisao de 05/09): se o profissional esconde a posicao, o
 * aviso continua — o cliente precisa chegar —, mas o texto nao diz que ele e o proximo.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueuePushService {

    /**
     * sem-quase-sua-vez-apos-aceite-agendado (29/10, CLI-37): quem tem agendamento aceito para daqui a mais de uma hora (outro dia,
     * ou mais tarde no mesmo dia) e o primeiro da lista de espera, mas nao e "quase a sua vez". O aviso espera chegar perto: a
     * flag {@code nextNotified} nao e marcada, e ele sai numa mutacao da fila quando o horario estiver a uma hora ou menos.
     */
    static final Duration NEAR_WINDOW = Duration.ofHours(1);

    private final AttendanceRepository attendanceRepository;
    private final PushNotificationPublisher pushPublisher;

    /**
     * DEC-128 (queda brusca da estimativa): um push so, no lugar do "sua vez adiantou", com o horario novo; o cliente toca, ve o horario
     * e responde "ok" ou "preciso de mais tempo" (as 4 opcoes, sem multa na janela de 20 min). Uma vez por queda
     * ({@code suddenDropNotified}); quem ja nao esta na janela nao recebe. Roda em toda mutacao da fila, qualquer que seja a causa.
     */
    private void notifySuddenDrops(List<Attendance> waiting, UUID establishmentId) {
        OffsetDateTime now = OffsetDateTime.now();
        for (Attendance a : waiting) {
            if (a.getClient() == null || a.getSuddenDropAt() == null || a.isSuddenDropNotified() || !a.isSuddenDropNoticeOpen(now)) {
                continue;
            }
            OffsetDateTime newTime = a.getScheduledTime();
            String hhmm = newTime == null ? "" : newTime.atZoneSameInstant(com.ospaulos.vupth.domain.shared.BusinessClock.zone())
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            Map<String, Object> data = new HashMap<>();
            data.put("type", "SUDDEN_DROP");
            data.put("attendanceId", a.getId().toString());
            data.put("establishmentId", establishmentId.toString());
            data.put("newTime", String.valueOf(newTime));
            data.put("previousTime", String.valueOf(a.getSuddenDropFrom()));
            data.put("windowUntil", String.valueOf(a.getSuddenDropWindowUntil()));
            pushPublisher.notifyUser(a.getClient().getId(), "Sua vez adiantou",
                    "Sua vez adiantou" + (hhmm.isEmpty() ? "" : " para as " + hhmm) + ". Veja o horário e confirme.", data);
            a.setSuddenDropNotified(true);
            attendanceRepository.save(a);
            log.info("[PUSH] sudden-drop — client={} attendance={} novo={}", a.getClient().getId(), a.getId(), newTime);
        }
    }

    /** O horario e daqui a mais de {@link #NEAR_WINDOW}? Sem horario, vale como perto (comportamento de sempre). */
    static boolean isFarAhead(OffsetDateTime scheduledTime, OffsetDateTime now) {
        return scheduledTime != null && scheduledTime.isAfter(now.plus(NEAR_WINDOW));
    }

    @Transactional
    public void notifyNextInLine(UUID establishmentId) {
        if (establishmentId == null) {
            return;
        }
        // Ordenado por (profissional, scheduledTime): a 1a linha de cada profissional e a frente da fila dele.
        List<Attendance> waiting = attendanceRepository.findWaitingQueueByEstablishment(establishmentId);
        if (waiting.isEmpty()) {
            return;
        }
        notifySuddenDrops(waiting, establishmentId);
        Set<UUID> handledProfessionals = new HashSet<>();
        for (Attendance head : waiting) {
            UUID professionalId = head.getProfessional() != null ? head.getProfessional().getId() : null;
            if (professionalId == null || !handledProfessionals.add(professionalId)) {
                continue; // ja tratamos a frente deste profissional
            }
            if (head.getClient() == null || head.isNextNotified()) {
                continue; // convidado, ou ja avisado nesta vez
            }
            if (isFarAhead(head.getScheduledTime(), OffsetDateTime.now())) {
                continue; // agendamento para depois: "e quase a sua vez" nao faz sentido
            }

            UUID clientId = head.getClient().getId();
            Map<String, Object> data = new HashMap<>();
            data.put("type", "QUEUE_NEXT_IN_LINE");
            data.put("attendanceId", head.getId().toString());
            data.put("establishmentId", establishmentId.toString());

            // Decisao de 05/09: com a posicao escondida pelo profissional, o push nao a revela
            boolean showPosition = !Boolean.FALSE.equals(head.getProfessional().getShowPosition());
            String body = showPosition ? "Você é o próximo da fila." : "Fique por perto: seu atendimento está para começar.";
            pushPublisher.notifyUser(clientId, "É quase a sua vez!", body, data);

            head.setNextNotified(true);
            attendanceRepository.save(head);
            log.info("[PUSH] next-in-line — client={} attendance={} estab={}", clientId, head.getId(), establishmentId);
        }
    }
}
