package com.ospaulos.vupth.domain.attendance.event;

import java.util.UUID;

/** O atendimento terminou (FINISHED). Quem ouve age depois do commit, sem atrasar nem quebrar o "Finalizar". */
public record AttendanceFinishedEvent(UUID attendanceId) {
}
