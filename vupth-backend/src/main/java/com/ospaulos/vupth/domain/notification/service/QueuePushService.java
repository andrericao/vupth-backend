package com.ospaulos.vupth.domain.notification.service;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueuePushService {

    private final AttendanceRepository attendanceRepository;
    private final PushNotificationPublisher pushPublisher;

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
        Set<UUID> handledProfessionals = new HashSet<>();
        for (Attendance head : waiting) {
            UUID professionalId = head.getProfessional() != null ? head.getProfessional().getId() : null;
            if (professionalId == null || !handledProfessionals.add(professionalId)) {
                continue; // ja tratamos a frente deste profissional
            }
            if (head.getClient() == null || head.isNextNotified()) {
                continue; // convidado, ou ja avisado nesta vez
            }

            UUID clientId = head.getClient().getId();
            Map<String, Object> data = new HashMap<>();
            data.put("type", "QUEUE_NEXT_IN_LINE");
            data.put("attendanceId", head.getId().toString());
            data.put("establishmentId", establishmentId.toString());

            pushPublisher.notifyUser(clientId, "É quase a sua vez!", "Você é o próximo da fila.", data);

            head.setNextNotified(true);
            attendanceRepository.save(head);
            log.info("[PUSH] next-in-line — client={} attendance={} estab={}", clientId, head.getId(), establishmentId);
        }
    }
}
