package com.ospaulos.vupth.domain.attendance.exception;

import java.util.UUID;

public class AttendanceNotFoundException extends RuntimeException {
    public AttendanceNotFoundException(UUID id) {
        super("Attendance not found with id: " + id);
    }
}
