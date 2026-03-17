package com.ospaulos.vupth.api.attendance.dto.request;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AttendanceRequest(
        UUID professionalID,
        UUID establishmentId,
        UUID clientId,
        List<UUID> serviceItemIds,
        AttendanceStatus status,
        OffsetDateTime scheduledTime, // must be ISO-8601 string — enforced by JacksonConfig.StrictOffsetDateTimeDeserializer
        Integer estimatedMinutes,
        OffsetDateTime local
) {
}
