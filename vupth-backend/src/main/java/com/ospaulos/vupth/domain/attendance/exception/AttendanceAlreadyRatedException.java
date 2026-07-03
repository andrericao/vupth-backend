package com.ospaulos.vupth.domain.attendance.exception;

import java.util.UUID;

/**
 * Avaliacao de atendimento (item #6). Lancada quando o cliente tenta avaliar um atendimento que
 * ja possui uma avaliacao — so e permitido 1 voto por atendimento ({@code attendance_id} UNIQUE).
 *
 * Mapeada para HTTP 409 com code ATTENDANCE_ALREADY_RATED.
 */
public class AttendanceAlreadyRatedException extends RuntimeException {
    public AttendanceAlreadyRatedException(UUID attendanceId) {
        super("Attendance " + attendanceId + " has already been rated.");
    }
}
