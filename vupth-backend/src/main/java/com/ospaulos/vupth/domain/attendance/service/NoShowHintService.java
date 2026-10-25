package com.ospaulos.vupth.domain.attendance.service;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.client.service.LateArrivalService;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * regra-do-atraso-na-fila-vazia (DEC-101): o profissional NAO e avisado a cada cliente que entra na fila. So num caso: ha um
 * unico cliente esperando, ele ja passou dos 10 min da hora comunicada (o NAO CHEGOU ja vale) e outro cliente acabou de entrar —
 * ai o profissional recebe um push para decidir se marca o NAO CHEGOU e chama o novo. Nunca derruba quem entrou na fila.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoShowHintService {

    private final AttendanceRepository attendanceRepository;
    private final LateArrivalService lateArrivalService;
    private final PushNotificationPublisher pushPublisher;

    /** @return se o aviso saiu */
    @Transactional(readOnly = true)
    public boolean onQueueEntry(UUID newcomerId) {
        try {
            Attendance newcomer = attendanceRepository.findByIdEager(newcomerId).orElse(null);
            if (newcomer == null || newcomer.getProfessional() == null || newcomer.getEstablishment() == null) {
                return false;
            }
            Professional professional = newcomer.getProfessional();
            List<Attendance> waiting = attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(
                    professional.getId(), newcomer.getEstablishment().getId());
            if (waiting.size() != 2 || waiting.get(0).getId().equals(newcomerId)
                    || !waiting.get(1).getId().equals(newcomerId)) {
                return false;
            }
            Attendance late = waiting.get(0);
            OffsetDateTime now = OffsetDateTime.now();
            if (now.isBefore(lateArrivalService.noShowAvailableAt(late, professional, now))) {
                return false;
            }
            String who = late.getClient() != null ? late.getClient().getFirstName() : late.getGuestName();
            int minutes = lateArrivalService.noShowWaitMinutes();
            pushPublisher.notifyUser(professional.getId(), "Cliente atrasado e fila andando",
                    (who == null || who.isBlank() ? "O cliente da vez" : who) + " está atrasado há mais de " + minutes
                            + " min e outro cliente entrou na fila. Se ele não chegou, marque NÃO CHEGOU e chame o próximo.",
                    Map.of("type", "NO_SHOW_AVAILABLE", "attendanceId", late.getId().toString(),
                            "establishmentId", String.valueOf(newcomer.getEstablishment().getId())));
            return true;
        } catch (RuntimeException e) {
            log.warn("[NoShowHint] nao deu para avisar o profissional: {}", e.getMessage());
            return false;
        }
    }
}
