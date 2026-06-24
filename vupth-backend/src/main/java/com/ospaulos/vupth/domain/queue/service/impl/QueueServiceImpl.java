package com.ospaulos.vupth.domain.queue.service.impl;

import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInProgressResponse;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;
import com.ospaulos.vupth.api.client.dto.response.ClientInQueueResponse;
import com.ospaulos.vupth.api.queue.dto.response.QueueResponse;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.attendance.service.AttendanceService;
import com.ospaulos.vupth.domain.queue.exception.ClientNotInQueueException;
import com.ospaulos.vupth.domain.queue.service.QueueService;
import com.ospaulos.vupth.domain.queue.service.QueueUtils;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@AllArgsConstructor
@org.springframework.transaction.annotation.Transactional
public class QueueServiceImpl implements QueueService {

    private final AttendanceService attendanceService;

    private final ProfessionalRepository professionalRepository;
    private final AttendanceRepository attendanceRepository;

    @Override
    public QueueResponse getQueueOverview(Professional professional, Establishment establishment) {

        QueueResponse queueBase = getQueueForProfessional(professional, establishment);

        Optional<Attendance> currentAttendance = professional.getCurrentAttendance();

        if (currentAttendance.isEmpty()) {
            return queueBase;
        }

        AttendanceInProgressResponse attendanceInProgressResponse =
                attendanceService.buildAttendanceInProgressResponse(currentAttendance.orElse(null));

        // Obtenha os attendances futuros (queue)
        List<Attendance> queue = professional.getScheduledAttendances();

        // Get applicable intervals
        OffsetDateTime now = OffsetDateTime.now();
        List<ProfessionalInterval> intervals = professional.getIntervals();

        int tempoRestanteAtendimentoAtual = attendanceInProgressResponse.remainingMinutes();
        int tempoRestanteFila = QueueUtils.calculateWaitingTimeForPosition(queue, intervals, now, queue.size());
        int totalRemaining = tempoRestanteAtendimentoAtual + tempoRestanteFila;

        int tempoEstimadoFila = QueueUtils.calculateWaitingTimeForPosition(queue, intervals, now, queue.size());
        int totalEstimated = currentAttendance.get().getEstimatedTotalMinutes() + tempoEstimadoFila;

        int totalElapsed = attendanceInProgressResponse.elapsedMinutes()
                + (totalEstimated - totalRemaining);

        return new QueueResponse(
                queueBase.professionalId(),
                queueBase.professionalName(),
                queueBase.establishmentId(),
                queueBase.establishmentName(),
                queueBase.size(),
                queueBase.attendancesInQueue(),
                totalEstimated,
                totalElapsed,
                totalRemaining,
                //pausedTime,
                attendanceInProgressResponse
        );
    }

    @Override
    public ClientInQueueResponse enqueueAttendance(Attendance attendance, Professional professional) {

        List<Attendance> queue = professional.getScheduledAttendances();
        queue.add(attendance);

        return queueWaitingTime(attendance);
    }

    @Override
    public QueueResponse enqueueGuestAttendance(Attendance attendance, Professional professional) {
        // O attendance já foi persistido pelo attendanceService.createGuestAttendance().
        // Não é necessário manipular a lista em memória — getQueueOverview usa query ao banco.
        return getQueueOverview(professional, attendance.getEstablishment());
    }

    @Override
    public ClientInQueueResponse queueWaitingTime(Attendance attendance) {

        Professional professional = attendance.getProfessional();
        Establishment establishment = attendance.getEstablishment();

        QueueResponse fullQueue = getQueueOverview(professional, establishment);

        int clientPosition = findClientPosition(fullQueue, attendance.getId());

        if (clientPosition == -1) {
            throw new ClientNotInQueueException(attendance.getId());
        }

        // ETA ESTAVEL do cliente (item #37 lock): atendimentos a frente (recalculado, ritmo da fila)
        // + restante do em progresso + refeicoes TRAVADAS no ingresso (nao saem por ritmo; so por
        // skip #41 / inicio / remocao). A projecao meal-aware (commit pre-req) segue valendo para o
        // snapshot da loja (cliente novo decidindo), nao para o ETA de quem ja esta na fila.
        List<Attendance> queueEntities = attendanceRepository
                .findWaitingQueueByProfessionalAndEstablishment(professional.getId(), establishment.getId());
        OffsetDateTime now = OffsetDateTime.now();
        LocalDate today = now.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();

        int currentRemaining = fullQueue.inAttendance() != null
                ? fullQueue.inAttendance().remainingMinutes() : 0;

        List<Attendance> ahead = queueEntities.subList(0, Math.min(clientPosition, queueEntities.size()));
        Set<UUID> lockedIds = attendance.getLockedIntervalIds();
        List<ProfessionalInterval> lockedMeals = professional.getIntervals().stream()
                .filter(iv -> lockedIds.contains(iv.getId()))
                .toList();

        int totalWaitingTime = QueueUtils.lockedClientWait(ahead, currentRemaining, lockedMeals, today);
        OffsetDateTime startTime = now.plusMinutes(totalWaitingTime);

        AttendanceInQueueResponse attendanceQueue = AttendanceInQueueResponse.fromEntity(attendance);

        UUID clientId = attendance.getClient() != null ? attendance.getClient().getId() : null;

        return new ClientInQueueResponse(
                attendance.getId(),
                clientId,
                clientPosition + 1,
                attendanceQueue,
                fullQueue.size(),
                fullQueue.totalEstimatedMinutes(),
                fullQueue.totalRemainingMinutes(),
                totalWaitingTime,
                startTime

        );
    }

    private QueueResponse getQueueForProfessional(Professional professional, Establishment establishment) {

        // IMPORTANTE: não usar professional.getScheduledAttendances() aqui.
        // Esse método lê professional.attendances (coleção LAZY em memória) que pode estar
        // desatualizada — especialmente logo após um save() de um novo guest attendance.
        // A query direta ao banco garante dados consistentes.
        List<Attendance> scheduledQueue = attendanceRepository
                .findWaitingQueueByProfessionalAndEstablishment(
                        professional.getId(),
                        establishment.getId()
                );

        List<AttendanceInQueueResponse> itemInfos = new ArrayList<>();

        int totalEstimated = 0;
        int totalRemaining = 0;

        for (Attendance attendance : scheduledQueue) {

            AttendanceInQueueResponse attendanceInfo = attendanceService.buildAttendanceInQueueResponse(attendance);

            int estimatedMinutes = attendance.getEstimatedTotalMinutes();
            totalEstimated += estimatedMinutes;
            totalRemaining += estimatedMinutes;

            itemInfos.add(attendanceInfo);
        }

        return new QueueResponse(
                professional.getId(),
                professional.getName(),
                establishment.getId(),
                establishment.getName(),
                itemInfos.size(),
                itemInfos,
                totalEstimated,
                0,
                totalRemaining,
                null
        );
    }

    @Override
    // TODO: Move this code to ProfessionalService class
    public void updateQueueEstimates(Professional professional) {

        OffsetDateTime nextAvailableTime = calculateNextAvailableTime(professional);

        // Inclui LATE (item #19): o atrasado fica na fila e precisa ser renormalizado junto,
        // mantendo a posicao relativa (senao seu horario antigo o jogaria pro fim).
        List<Attendance> queuedAttendances = professional.getWaitingAttendances();

        updateQueueScheduledTimes(queuedAttendances, nextAvailableTime);
    }

    private OffsetDateTime calculateNextAvailableTime(Professional professional) {
        return professional.getCurrentAttendance()
                .map(currentAttendance -> {
                    int remainingTime = currentAttendance.getRemainingMinutes();

                    log.info("Professional {} has is_active attendance - {} minutes remaining",
                            professional.getId(), remainingTime);

                    return OffsetDateTime.now().plusMinutes(remainingTime);
                })
                .orElseGet(() -> {
                    log.info("Professional {} available now", professional.getId());
                    return OffsetDateTime.now();
                });
    }

    private void updateQueueScheduledTimes(List<Attendance> queuedAttendances, OffsetDateTime startTime) {

        if(queuedAttendances.isEmpty()){
            log.info("No queued attendances to update!");
            return;
        }

        OffsetDateTime currentTime = startTime;

        for (Attendance attendance : queuedAttendances) {
            OffsetDateTime oldTime = attendance.getScheduledTime();

            attendance.setScheduledTime(currentTime);
            currentTime = currentTime.plusMinutes(attendance.getEstimatedMinutes());

            Attendance saved = attendanceRepository.save(attendance);

            log.debug("⏰ Updated attendance {} - Old: {} → New: {}",
                    attendance.getId(), oldTime, saved.getScheduledTime());
        }

        log.info("Updated {} attendances in queue", queuedAttendances.size());
    }


        private int findClientPosition(QueueResponse queue, UUID attendanceId) {
        for (int i = 0; i < queue.size(); i++) {
            if (queue.attendancesInQueue().get(i).id().equals(attendanceId))
                return i;
        }
        return -1;
    }

}