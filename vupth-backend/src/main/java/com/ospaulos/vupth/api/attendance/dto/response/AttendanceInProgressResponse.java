package com.ospaulos.vupth.api.attendance.dto.response;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AttendanceInProgressResponse(
        UUID attendanceId,
        UUID establishmentId,
        String establishmentName,
        UUID professionalId,
        String professionalName,
        UUID clientId,
        String clientName,
        List<AttendanceInQueueResponse.ServiceItemSummary> services,
        OffsetDateTime scheduledTime,
        AttendanceStatus status,
        BigDecimal totalValue,
        boolean isPaused,
        OffsetDateTime startTime,
        int elapsedMinutes,
        int pausedTimeMinutes,
        int totalElapsedMinutes,
        int remainingMinutes
) {
}