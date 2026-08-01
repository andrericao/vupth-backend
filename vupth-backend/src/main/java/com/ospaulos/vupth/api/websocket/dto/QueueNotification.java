package com.ospaulos.vupth.api.websocket.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
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
 *
 * <p>{@code graceUntil} so vem preenchido no {@code CLOSING_PROMPT} (S1 fatia 5 / auto-fechamento):
 * e o instante-limite da carencia de 15min (server-authoritative) para o front-Partner renderizar o
 * countdown do prompt "quer fechar?"; {@code null} em todos os demais eventos.</p>
 */
public record QueueNotification(
        String type,
        UUID attendanceId,
        UUID establishmentId,
        String message,
        UUID cutRequestId,
        OffsetDateTime graceUntil,
        Instant timestamp
) {
    public static QueueNotification of(String type, UUID attendanceId, UUID establishmentId, String message) {
        return new QueueNotification(type, attendanceId, establishmentId, message, null, null, Instant.now());
    }

    /** Anexa o {@code cutRequestId} a esta notificacao (eventos de pedido de corte). */
    public QueueNotification withCutRequestId(UUID cutRequestId) {
        return new QueueNotification(type, attendanceId, establishmentId, message, cutRequestId, graceUntil, timestamp);
    }

    /** Anexa o {@code graceUntil} a esta notificacao (prompt de auto-fechamento — CLOSING_PROMPT). */
    public QueueNotification withGraceUntil(OffsetDateTime graceUntil) {
        return new QueueNotification(type, attendanceId, establishmentId, message, cutRequestId, graceUntil, timestamp);
    }
}
