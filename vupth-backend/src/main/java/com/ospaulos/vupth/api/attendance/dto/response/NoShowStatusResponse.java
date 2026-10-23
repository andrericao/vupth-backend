package com.ospaulos.vupth.api.attendance.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * regra-do-atraso-na-fila-vazia (DEC-101): se o NAO CHEGOU ja vale para este atendimento, e quanto falta. O relogio e o do
 * servidor — o app so mostra {@code seconds_left}. {@code reason}: NOT_FIRST (so o primeiro da fila) ou TOO_EARLY (antes dos 10 min);
 * nulo quando {@code can_mark_no_show}. {@code available_at} e {@code seconds_left} saem quando o atendimento e o primeiro.
 */
public record NoShowStatusResponse(
        @JsonProperty("attendance_id") UUID attendanceId,
        @JsonProperty("is_first") boolean first,
        @JsonProperty("can_mark_no_show") boolean canMarkNoShow,
        @JsonProperty("reason") String reason,
        @JsonProperty("available_at") OffsetDateTime availableAt,
        @JsonProperty("seconds_left") Long secondsLeft,
        @JsonProperty("wait_minutes") int waitMinutes
) {
}
