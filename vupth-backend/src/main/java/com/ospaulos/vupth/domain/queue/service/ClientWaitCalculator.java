package com.ospaulos.vupth.domain.queue.service;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * A espera que o CLIENTE le ({@code estimatedWaitingTime} do wait-time) — conta unica.
 *
 * <p>Duas coisas precisam do mesmo numero: a tela ("faltam 25 min") e a multa de saida, cuja regua e o
 * horario comunicado (D4). Se cada lado somasse por conta propria, o app mostraria R$ 3 e o servidor
 * cobraria R$ 5. Mora fora dos dois services porque o QueueService ja depende do AttendanceService.
 *
 * <p>ETA estavel (item #37 lock): restante do atendimento em curso + atendimentos a frente
 * (recalculados, ritmo da fila) + refeicoes TRAVADAS no ingresso.
 */
@Component
@RequiredArgsConstructor
public class ClientWaitCalculator {

    private final AttendanceRepository attendanceRepository;

    /** Minutos ate a vez do cliente; vazio se o atendimento nao esta na fila de espera. */
    public OptionalInt clientWaitMinutes(Attendance attendance, OffsetDateTime now) {
        Professional professional = attendance.getProfessional();
        if (professional == null || attendance.getEstablishment() == null) {
            return OptionalInt.empty();
        }
        List<Attendance> waiting = attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(
                professional.getId(), attendance.getEstablishment().getId());
        int position = -1;
        for (int i = 0; i < waiting.size(); i++) {
            if (waiting.get(i).getId().equals(attendance.getId())) {
                position = i;
                break;
            }
        }
        if (position < 0) {
            return OptionalInt.empty();
        }

        int currentRemaining = professional.getCurrentAttendance()
                .map(current -> current.getRemainingMinutes(now))
                .orElse(0);
        Set<UUID> lockedIds = attendance.getLockedIntervalIds();
        List<ProfessionalInterval> lockedMeals = professional.getIntervals().stream()
                .filter(iv -> lockedIds.contains(iv.getId()))
                .toList();

        return OptionalInt.of(QueueUtils.lockedClientWait(
                waiting.subList(0, position), currentRemaining, lockedMeals, BusinessClock.dateAt(now)));
    }
}
