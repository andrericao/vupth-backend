package com.ospaulos.vupth.domain.queue.service;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

public class QueueUtils {

    /**
     * Calculates the wait time for a client at position X in the queue,
     * considerando as regras de prioridade entre attendance e intervals.
     *
     * @param queue         Ordered list of attendances (position 0 = next to be served)
     * @param intervals   Lista de intervals do dia (com startTime definido)
     * @param now          Momento atual
     * @param positionX     Client position in the queue (0 = next to be served)
     * @return wait time in minutes, already including intervals
     */

    public static int calculateWaitingTimeForPosition(
            List<Attendance> queue,
            List<ProfessionalInterval> intervals,
            OffsetDateTime now,
            int positionX
    ) {
        // positionX == queue.size() is valid: it means "total wait for the entire queue".
        // positionX == 0 means "no items to wait for" → 0 minutes.
        if (positionX < 0 || positionX > queue.size()) {
            throw new IllegalArgumentException("Invalid position in queue: " + positionX + " (size=" + queue.size() + ")");
        }
        if (positionX == 0 || queue.isEmpty()) {
            return 0;
        }

        OffsetDateTime nextTimeFree = now;
        int totalTime = 0;

        // Ordena intervals pelo startTime
        List<ProfessionalInterval> pendingIntervals = new ArrayList<>(intervals);
        pendingIntervals.sort(Comparator.comparing(ProfessionalInterval::getStartTime));

        for (int i = 0; i < positionX; i++) {
            Attendance attendance = queue.get(i);
            int duration = attendance.getEstimatedTotalMinutes();
            OffsetDateTime scheduledStartService = nextTimeFree;
            OffsetDateTime scheduledFinishService = scheduledStartService.plusMinutes(duration);

            Iterator<ProfessionalInterval> it = pendingIntervals.iterator();
            while (it.hasNext()) {
                ProfessionalInterval interval = it.next();
                OffsetDateTime intervalStartInt = interval.getStartTime();
                OffsetDateTime intervalFinishInt = intervalStartInt.plusMinutes(interval.getDurationMinutes());

                // If the interval has already ended, skip it
                if (!intervalFinishInt.isAfter(scheduledStartService)) {
                    it.remove();
                    continue;
                }

                boolean attendanceFinishesBefore15Minutes =
                        !scheduledStartService.isBefore(intervalStartInt.minusMinutes(15)) && scheduledStartService.isBefore(intervalStartInt);

                // Regra de prioridade
                if (attendanceFinishesBefore15Minutes) {
                    if (!scheduledFinishService.isAfter(intervalStartInt.plusMinutes(30))) {
                        break;
                    } else {
                        // Atendimento excede o limite, adianta o interval para depois do attendance.IN_PROGRESS
                        interval.setStartTime(scheduledFinishService);
                        intervalStartInt = scheduledFinishService;
                        intervalFinishInt = intervalStartInt.plusMinutes(interval.getDurationMinutes());
                    }
                }

                // If the attendance overlaps the interval (starts before the interval ends)
                if (intervalStartInt.isBefore(scheduledFinishService) && intervalFinishInt.isAfter(scheduledStartService)) {
                    // Atendimento pausado durante o interval
                    totalTime += Duration.between(nextTimeFree, intervalStartInt).toMinutes();
                    totalTime += interval.getDurationMinutes();
                    nextTimeFree = intervalFinishInt;
                    scheduledStartService = nextTimeFree;
                    scheduledFinishService = scheduledStartService.plusMinutes(duration);
                    it.remove();
                    // After the interval there may be another, so repeat the while loop
                    continue;
                }
            }
            totalTime += Duration.between(nextTimeFree, scheduledFinishService).toMinutes();
            nextTimeFree = scheduledFinishService;
        }

        // If currently within an interval, add remaining interval time to totalTime
        for (ProfessionalInterval interval : pendingIntervals) {
            OffsetDateTime intervalStartInt = interval.getStartTime();
            OffsetDateTime intervalFinishInt = intervalStartInt.plusMinutes(interval.getDurationMinutes());
            if (!now.isBefore(intervalStartInt) && now.isBefore(intervalFinishInt)) {
                totalTime += Duration.between(now, intervalFinishInt).toMinutes();
                break; // can only be within one interval at a time
            }
        }
        return totalTime;
    }
}
