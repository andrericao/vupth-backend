package com.ospaulos.vupth.api.websocket.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Notificacao pessoal enviada a um cliente via WebSocket
 * (destino /user/queue/notifications).
 *
 * type: ATTENDANCE_STARTED, ATTENDANCE_FINISHED, etc.
 *
 * <p>{@code cutRequestId} so vem preenchido nos eventos de pedido de corte (BUG5 C2:
 * CUT_REQUESTED/CUT_ACCEPTED/CUT_REFUSED/CUT_EXPIRED); {@code null} em todos os demais.
 * Existe para o front identificar o pedido sem depender do campo {@code attendanceId}
 * (que so carrega um Attendance real, ex.: o gerado no aceite).</p>
 */
public record QueueNotification(
        String type,
        UUID attendanceId,
        UUID establishmentId,
        String message,
        UUID cutRequestId,
        Instant timestamp
) {
    public static QueueNotification of(String type, UUID attendanceId, UUID establishmentId, String message) {
        return new QueueNotification(type, attendanceId, establishmentId, message, null, Instant.now());
    }

    /** Anexa o {@code cutRequestId} a esta notificacao (eventos de pedido de corte). */
    public QueueNotification withCutRequestId(UUID cutRequestId) {
        return new QueueNotification(type, attendanceId, establishmentId, message, cutRequestId, timestamp);
    }
}
