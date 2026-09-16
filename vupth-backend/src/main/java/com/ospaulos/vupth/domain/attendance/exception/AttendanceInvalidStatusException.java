package com.ospaulos.vupth.domain.attendance.exception;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;

public class AttendanceInvalidStatusException extends RuntimeException {
    public AttendanceInvalidStatusException(AttendanceStatus current, AttendanceStatus required) {
        super(String.format(
            "Invalid operation: current status is '%s' but required is '%s'",
            current, required));
    }
    public AttendanceInvalidStatusException(String message) {
        super(message);
    }

    public static AttendanceInvalidStatusException notFinishable(AttendanceStatus current) {
        return new AttendanceInvalidStatusException(String.format(
            "Invalid operation: current status is '%s' but required is '%s' or '%s'",
            current, AttendanceStatus.IN_PROGRESS, AttendanceStatus.PAUSED));
    }
}
