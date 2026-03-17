package com.ospaulos.vupth.api.client.dto.response;

import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;

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
        OffsetDateTime startTime
) {}