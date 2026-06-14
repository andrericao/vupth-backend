package com.ospaulos.vupth.api.websocket.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Notificacao pessoal enviada a um cliente via WebSocket
 * (destino /user/queue/notifications).
 *
 * type: ATTENDANCE_STARTED, ATTENDANCE_FINISHED, etc.
 */
public record QueueNotification(
        String type,
        UUID attendanceId,
        UUID establishmentId,
        String message,
        Instant timestamp
) {
    public static QueueNotification of(String type, UUID attendanceId, UUID establishmentId, String message) {
        return new QueueNotification(type, attendanceId, establishmentId, message, Instant.now());
    }
}
