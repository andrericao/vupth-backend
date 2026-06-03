package com.ospaulos.vupth.api.attendance.dto.response;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public record AttendanceListResponse(
        UUID id,
        UUID clientId,
        String clientName,
        UUID professionalId,
        String professionalName,
        UUID establishmentId,
        List<AttendanceInQueueResponse.ServiceItemSummary> services,
        AttendanceStatus status,
        BigDecimal totalValue,
        OffsetDateTime scheduledTime,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        Integer durationInMinutes
) {
    public static AttendanceListResponse fromEntity(Attendance attendance) {
        UUID clientId = attendance.getClient() != null ? attendance.getClient().getId() : null;
        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : attendance.getGuestName();

        return new AttendanceListResponse(
                attendance.getId(),
                clientId,
                clientName,
                attendance.getProfessional().getId(),
                attendance.getProfessional().getName(),
                attendance.getEstablishment().getId(),
                attendance.getServiceItems().stream()
                        .map(AttendanceInQueueResponse.ServiceItemSummary::fromEntity)
                        .collect(Collectors.toList()),
                attendance.getStatus(),
                attendance.getTotalValue(),
                attendance.getScheduledTime(),
                attendance.getStartTime(),
                attendance.getEndTime(),
                attendance.getDurationInMinutes()
        );
    }
}
