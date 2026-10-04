package com.ospaulos.vupth.domain.attendance.exception;

import java.util.UUID;

/**
 * Adicionar servico a atendimento ja na fila (adicionar-servico-na-fila, Client C8): o tempo a mais so
 * cabe se nao houver ninguem depois — regra do Andre (15/09): quem entrou na fila atras, ou uma
 * intencao marcada para logo depois, perderia o horario. 409 {@code ATTENDANCE_NO_ROOM_FOR_SERVICE};
 * nada muda no atendimento. O mesmo pedido vale mais tarde, quando a fila atras dele andar.
 */
public class AttendanceNoRoomForServiceException extends RuntimeException {

    private final UUID attendanceId;

    private AttendanceNoRoomForServiceException(UUID attendanceId, String message) {
        super(message);
        this.attendanceId = attendanceId;
    }

    /** Alguem entrou na fila depois deste atendimento. */
    public static AttendanceNoRoomForServiceException someoneBehind(UUID attendanceId) {
        return new AttendanceNoRoomForServiceException(attendanceId,
                "Ha clientes na fila depois de voce: o tempo a mais atrasaria a vez deles.");
    }

    /** Uma intencao marcada comeca antes do novo fim da fila. */
    public static AttendanceNoRoomForServiceException intentionAhead(UUID attendanceId) {
        return new AttendanceNoRoomForServiceException(attendanceId,
                "Ha um horario marcado logo depois de voce: o tempo a mais nao cabe antes dele.");
    }

    public UUID getAttendanceId() {
        return attendanceId;
    }
}
