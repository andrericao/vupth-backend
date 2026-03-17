package com.ospaulos.vupth.api.attendance.dto.request;

import java.util.UUID;

public record CancelAttendanceRequest(
        UUID clientId
) {
}
