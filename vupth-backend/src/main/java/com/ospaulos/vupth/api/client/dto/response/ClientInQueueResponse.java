package com.ospaulos.vupth.api.client.dto.response;

import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ClientInQueueResponse(
        UUID attendanceId,
        UUID clientId,
        int position,
        AttendanceInQueueResponse attendanceInQueueResponse,
        int totalQueueSize,
        int totalEstimatedMinutes,
        int totalRemainingMinutes,
        int estimatedWaitingTime,
        OffsetDateTime startTime,
        /** Ate quando sair da fila e de graca (D4, tolerancia de 24/09); nulo em atendimento anterior a regra. */
        OffsetDateTime toleranceUntil,
        /** Quanto custa sair AGORA — o valor do aviso "Sim / Nao"; 0.00 dentro da tolerancia ou com 1h ou mais. */
        BigDecimal exitPenaltyValue,
        /** Atrasos acumulados do cliente e quantos faltam para a multa (D4); 0 / 3 para convidado. */
        int lateCount,
        int latesUntilPenalty
) {}