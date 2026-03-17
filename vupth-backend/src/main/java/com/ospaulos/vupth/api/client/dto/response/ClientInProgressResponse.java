package com.ospaulos.vupth.api.client.dto.response;

import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInProgressResponse;

import java.util.UUID;

public record ClientInProgressResponse(
        UUID attendanceId,
        UUID clientId,
        AttendanceInProgressResponse attendanceProgress
) {
}