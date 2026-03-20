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
import com.ospaulos.vupth.domain.queue.service.QueueService;
import com.ospaulos.vupth.domain.queue.service.QueueUtils;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

        // Scheduled attendances in the queue
        List<Attendance> queue = professional.getScheduledAttendances();

        // Factor professional intervals (breaks) into queue time calculations.
        // QueueUtils.calculateWaitingTimeForPosition accounts for intervals that overlap
        // with scheduled attendances, adding break durations to the total wait.
        OffsetDateTime now = OffsetDateTime.now();
        List<ProfessionalInterval> intervals = professional.getIntervals();

        int tempoRestanteAtendimentoAtual = attendanceInProgressResponse.remainingMinutes();
        // Calculate total queue time including interval breaks
        int tempoFila = QueueUtils.calculateWaitingTimeForPosition(queue, intervals, now, queue.size());
        int totalRemaining = tempoRestanteAtendimentoAtual + tempoFila;

        int totalEstimated = currentAttendance.get().getEstimatedTotalMinutes() + tempoFila;

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

        List<Attendance> queue = professional.getScheduledAttendances();
        queue.add(attendance);

        return getQueueOverview(professional, attendance.getEstablishment());
    }

    @Override
    public ClientInQueueResponse queueWaitingTime(Attendance attendance) {

        // Define the attendance scheduled start time based on current queue state
        setAttendanceScheduledTime(attendance);

        QueueResponse fullQueue = getQueueOverview(
                attendance.getProfessional(),
                attendance.getEstablishment());

        int clientPosition = findClientPosition(fullQueue, attendance.getId());

        if (clientPosition == -1) {
            throw new IllegalStateException("Client not found in queue");
        }

        WaitingTimeCalculation calculation = calculateWaitingTime(fullQueue, clientPosition);
        int totalWaitingTime = calculation.waitingTimeMinutes;
        OffsetDateTime startTime = calculation.estimatedStartTime;

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

        List<Attendance> scheduledQueue = professional.getScheduledAttendances();
        Optional<Attendance> currentAttendance = professional.getCurrentAttendance();

        List<AttendanceInQueueResponse> itemInfos = new ArrayList<>();

        // Start with the current IN_PROGRESS attendance contribution (if any)
        int totalEstimated = 0;
        int totalElapsed = 0;
        int totalRemaining = 0;

        if (currentAttendance.isPresent()) {
            Attendance current = currentAttendance.get();
            totalEstimated += current.getEstimatedTotalMinutes();
            totalElapsed += current.getServiceDurationInMinutes();
            totalRemaining += current.getRemainingMinutes();
        }

        // Add each SCHEDULED attendance to the totals
        for (Attendance attendance : scheduledQueue) {

            AttendanceInQueueResponse attendanceInfo = attendanceService.buildAttendanceInQueueResponse(attendance);

            int estimatedMinutes = attendance.getEstimatedTotalMinutes();
            totalEstimated += estimatedMinutes;
            // Scheduled attendances haven't started, so their full estimate counts as remaining
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
                totalElapsed,
                totalRemaining,
                null
        );
    }

    @Override
    // TODO: Move this code to ProfessionalService class
    public void updateQueueEstimates(Professional professional) {

        OffsetDateTime nextAvailableTime = calculateNextAvailableTime(professional);

        List<Attendance> queuedAttendances = professional.getScheduledAttendances();

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

    private WaitingTimeCalculation calculateWaitingTime(QueueResponse fullQueue, int clientPosition) {
        if (clientPosition == 0) {
            int waitingTime = fullQueue.inAttendance() != null ?
                    fullQueue.inAttendance().remainingMinutes() : 0;
            return new WaitingTimeCalculation(waitingTime, OffsetDateTime.now().plusMinutes(waitingTime));
        }

        int totalWaitingTime = 0;

        // Remaining time of the current IN_PROGRESS attendance
        if (fullQueue.inAttendance() != null) {
            totalWaitingTime += fullQueue.inAttendance().remainingMinutes();
        }

        // Time of attendances ahead in the queue
        for (int i = 0; i < clientPosition; i++) {
            AttendanceInQueueResponse attendance = fullQueue.attendancesInQueue().get(i);
            totalWaitingTime += attendance.estimatedMinutes() != null ? attendance.estimatedMinutes() : 0;
        }

        // Add professional interval/break time that falls within the estimated wait window.
        // If the professional has a scheduled break before this client's turn,
        // that break duration must be added to the total wait.
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime estimatedStart = now.plusMinutes(totalWaitingTime);

        // Find the professional from the queue context to get intervals
        Professional professional = professionalRepository.findById(fullQueue.professionalId()).orElse(null);
        if (professional != null) {
            List<ProfessionalInterval> intervals = professional.getIntervals();
            for (ProfessionalInterval interval : intervals) {
                if (interval.getStartTime() != null) {
                    OffsetDateTime intervalStart = interval.getStartTime();
                    OffsetDateTime intervalEnd = intervalStart.plusMinutes(interval.getDurationMinutes());

                    // If the interval falls within the wait window [now, estimatedStart],
                    // add its duration to the total waiting time
                    if (intervalStart.isAfter(now) && intervalStart.isBefore(estimatedStart)) {
                        totalWaitingTime += interval.getDurationMinutes();
                        // Recalculate estimated start since the break shifted it
                        estimatedStart = now.plusMinutes(totalWaitingTime);
                    }
                    // If we are currently inside an interval, add remaining interval time
                    else if (!now.isBefore(intervalStart) && now.isBefore(intervalEnd)) {
                        int remainingIntervalMinutes = (int) java.time.Duration.between(now, intervalEnd).toMinutes();
                        totalWaitingTime += remainingIntervalMinutes;
                        estimatedStart = now.plusMinutes(totalWaitingTime);
                    }
                }
            }
        }

        return new WaitingTimeCalculation(totalWaitingTime, OffsetDateTime.now().plusMinutes(totalWaitingTime));
    }


    private static class WaitingTimeCalculation {
        final int waitingTimeMinutes;
        final OffsetDateTime estimatedStartTime;

        WaitingTimeCalculation(int waitingTimeMinutes, OffsetDateTime estimatedStartTime) {
            this.waitingTimeMinutes = waitingTimeMinutes;
            this.estimatedStartTime = estimatedStartTime;
        }
    }

    /**
     * Defines the scheduled start time for an attendance entering the queue.
     * - If queue is empty and no current attendance: now
     * - If there's a current IN_PROGRESS attendance: its estimated end time
     * - If there are SCHEDULED attendances: last scheduled attendance's end time
     */
    private void setAttendanceScheduledTime(Attendance attendance) {
        Professional professional = attendance.getProfessional();
        Optional<Attendance> currentAttendance = professional.getCurrentAttendance();
        List<Attendance> scheduledQueue = professional.getScheduledAttendances();

        OffsetDateTime scheduledTime;

        if (scheduledQueue.isEmpty() && currentAttendance.isEmpty()) {
            // Queue is empty and no current attendance - start now
            scheduledTime = OffsetDateTime.now();
        } else if (!scheduledQueue.isEmpty()) {
            // There are scheduled attendances - start after the last one ends
            Attendance lastScheduled = scheduledQueue.get(scheduledQueue.size() - 1);
            OffsetDateTime lastScheduledStart = lastScheduled.getScheduledTime() != null
                    ? lastScheduled.getScheduledTime()
                    : OffsetDateTime.now();
            scheduledTime = lastScheduledStart.plusMinutes(lastScheduled.getEstimatedTotalMinutes());
        } else {
            // Only a current IN_PROGRESS attendance exists - start after it ends
            Attendance current = currentAttendance.get();
            scheduledTime = OffsetDateTime.now().plusMinutes(current.getRemainingMinutes());
        }

        attendance.setScheduledTime(scheduledTime);
        attendanceRepository.save(attendance);
    }
}