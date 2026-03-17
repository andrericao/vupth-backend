package com.ospaulos.vupth.domain.attendance.exception;

import java.util.UUID;

public class AttendanceAlreadyInProgressException extends RuntimeException {
    public AttendanceAlreadyInProgressException(UUID professionalId) {
        super("Professional '" + professionalId + "' already has an attendance in progress");
    }
}
