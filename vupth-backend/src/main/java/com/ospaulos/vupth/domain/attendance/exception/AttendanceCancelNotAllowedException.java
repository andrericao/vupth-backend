package com.ospaulos.vupth.domain.attendance.exception;

import java.util.UUID;

/**
 * Thrown when a CLIENT tries to cancel an attendance that is already IN_PROGRESS.
 * Only a PROFESSIONAL can cancel an attendance that has already started.
 * This is a security guard — the client UI never exposes this action, but the
 * endpoint must protect against direct API calls.
 */
public class AttendanceCancelNotAllowedException extends RuntimeException {
    public AttendanceCancelNotAllowedException(UUID attendanceId) {
        super(String.format(
            "Attendance '%s' is IN_PROGRESS. Only the professional can cancel an attendance that has already started.",
            attendanceId));
    }
}
