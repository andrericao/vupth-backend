package com.ospaulos.vupth.domain.attendance;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

public class AttendanceWaitTimeCalculator {
    /**
     * Advances the time past the next interval if it falls within one.
     */

    private static OffsetDateTime advanceAfterInterval(OffsetDateTime data, List<ProfessionalInterval> pendingIntervals) {
        for (ProfessionalInterval interval : pendingIntervals) {
            OffsetDateTime ini = interval.getStartTime();
            OffsetDateTime end = ini.plusMinutes(interval.getDurationMinutes());
            if (!data.isBefore(ini) && data.isBefore(end)) {
                return end;
            }
        }
        return data;
    }

    public static int calculateWaitingTimeForPosition(
            List<Attendance> queue,
            List<ProfessionalInterval> intervals,
            OffsetDateTime now,
            int positionX,
            int minutesBlock // 15 ou 30 normalmente
    ) {
        if (positionX < 0 || positionX >= queue.size()) {
            throw new IllegalArgumentException("Invalid position in queue!");
        }

        OffsetDateTime nextFree = advanceAfterInterval(now, intervals);

        int totalTime = 0;
        List<ProfessionalInterval> pendingIntervals = new ArrayList<>(intervals);
        pendingIntervals.sort(Comparator.comparing(ProfessionalInterval::getStartTime));

        for (int i = 0; i < positionX; i++) {
            Attendance attendance = queue.get(i);
            int duration = attendance.getEstimatedTotalMinutes();
            nextFree = advanceAfterInterval(nextFree, pendingIntervals);

            OffsetDateTime scheduledStart = nextFree;
            OffsetDateTime scheduledEnd = scheduledStart.plusMinutes(duration);

            Iterator<ProfessionalInterval> it = pendingIntervals.iterator();
            while (it.hasNext()) {
                ProfessionalInterval intervalo = it.next();
                OffsetDateTime iniInt = intervalo.getStartTime();
                OffsetDateTime endInt = iniInt.plusMinutes(intervalo.getDurationMinutes());

                if (!endInt.isAfter(scheduledStart)) {
                    it.remove();
                    continue;
                }

                boolean attendanceNos15MinAntes =
                        !scheduledStart.isBefore(iniInt.minusMinutes(15)) && scheduledStart.isBefore(iniInt);

                if (attendanceNos15MinAntes) {
                    if (!scheduledEnd.isAfter(iniInt.plusMinutes(minutesBlock))) {
                        // Attendance can be moved before the interval (keeps nextFree)
                        // Interval starts after this attendance, so just skip the interval here
                        continue;
                    } else {
                        // Atendimento excede o limite, adianta o intervalo para depois do attendance
                        intervalo.setStartTime(scheduledEnd);
                        iniInt = scheduledEnd;
                        endInt = iniInt.plusMinutes(intervalo.getDurationMinutes());
                    }
                }

                if (iniInt.isBefore(scheduledEnd) && endInt.isAfter(scheduledStart)) {
                    // Pausa durante o intervalo
                    if (scheduledEnd.isAfter(iniInt)) {
                        totalTime += Duration.between(nextFree, iniInt).toMinutes();
                        totalTime += intervalo.getDurationMinutes();
                        nextFree = endInt;
                        scheduledStart = nextFree;
                        scheduledEnd = scheduledStart.plusMinutes(duration);
                        it.remove();
                        continue;
                    }
                }
            }
            totalTime += Duration.between(nextFree, scheduledEnd).toMinutes();
            nextFree = scheduledEnd;
        }

        // If currently within an interval, add remaining interval time to totalTime
        for (ProfessionalInterval interval : pendingIntervals) {
            OffsetDateTime iniInt = interval.getStartTime();
            OffsetDateTime endInt = iniInt.plusMinutes(interval.getDurationMinutes());
            if (!now.isBefore(iniInt) && now.isBefore(endInt)) {
                totalTime += Duration.between(now, endInt).toMinutes();
                break;
            }
        }
        return totalTime;
    }
}
