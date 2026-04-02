package com.ospaulos.vupth.domain.queue.exception;

import java.util.UUID;

public class ClientNotInQueueException extends RuntimeException {
    public ClientNotInQueueException(UUID attendanceId) {
        super("Attendance '" + attendanceId + "' not found in queue");
    }
}
