package com.ospaulos.vupth.api.queue.dto.response;

import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInProgressResponse;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;

import java.util.List;
import java.util.UUID;

public record QueueResponse(
        UUID professionalId,
        String professionalName,
        UUID establishmentId,
        String establishmentName,
        int size,
        List<AttendanceInQueueResponse> attendancesInQueue,
        int totalEstimatedMinutes,
        int totalElapsedMinutes,
        int totalRemainingMinutes,
        //int pausedTime,
        AttendanceInProgressResponse inAttendance
) {}