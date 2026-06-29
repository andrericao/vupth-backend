package com.ospaulos.vupth.api.attendance.dto.request;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AttendanceRequest(
        UUID professionalId,
        UUID establishmentId,
        UUID clientId,
        List<UUID> serviceItemIds,
        AttendanceStatus status,
        OffsetDateTime scheduledTime, // must be ISO-8601 string — enforced by JacksonConfig.StrictOffsetDateTimeDeserializer
        Integer estimatedMinutes,
        OffsetDateTime local,
        // Item #40 (intencao de fila): horario-alvo opcional. Quando presente, o cliente entra como
        // INTENCAO (cede a vez ate o previsto encostar no alvo). Null = ingresso normal (walk-in).
        OffsetDateTime targetTime
) {
    /**
     * Construtor de compatibilidade (ingresso normal / walk-in): sem horario-alvo.
     * Delega ao canonico com {@code targetTime = null}. Jackson continua usando o
     * construtor canonico (9 componentes) para desserializar o JSON.
     */
    public AttendanceRequest(
            UUID professionalId,
            UUID establishmentId,
            UUID clientId,
            List<UUID> serviceItemIds,
            AttendanceStatus status,
            OffsetDateTime scheduledTime,
            Integer estimatedMinutes,
            OffsetDateTime local) {
        this(professionalId, establishmentId, clientId, serviceItemIds, status,
                scheduledTime, estimatedMinutes, local, null);
    }
}
