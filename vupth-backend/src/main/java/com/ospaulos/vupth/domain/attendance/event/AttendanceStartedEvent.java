package com.ospaulos.vupth.domain.attendance.event;

import java.util.UUID;

/** O atendimento iniciou (IN_PROGRESS). A campanha de link consome a vaga aqui, nao no fim (DEC-124). */
public record AttendanceStartedEvent(UUID attendanceId) {
}
