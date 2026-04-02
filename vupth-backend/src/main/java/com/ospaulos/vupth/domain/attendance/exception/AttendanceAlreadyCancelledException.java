package com.ospaulos.vupth.domain.attendance.exception;

import java.util.UUID;

public class AttendanceAlreadyCancelledException extends RuntimeException {
    public AttendanceAlreadyCancelledException(UUID attendanceId) {
        super(String.format("Attendance '%s' is already CANCELLED", attendanceId));
    }
}
