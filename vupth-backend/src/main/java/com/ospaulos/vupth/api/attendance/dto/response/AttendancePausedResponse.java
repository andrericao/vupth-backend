package com.ospaulos.vupth.api.attendance.dto.response;

import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AttendancePausedResponse(
        UUID id,
        UUID clientId,
        String clientName,
        AttendanceStatus status,
        OffsetDateTime startTime,
        OffsetDateTime pausedAt,
        Integer elapsedMinutes,
        Integer pausedDurationInMinutes,
        Integer remainingMinutes,
        BigDecimal totalValue,
        List<ServiceItemSummary> services
) {
    public record ServiceItemSummary(
            UUID id,
            String customName,
            Integer estimatedMinutes
    ) {
        public static ServiceItemSummary fromEntity(ServiceItem service) {
            return new ServiceItemSummary(
                    service.getId(),
                    service.getCustomName(),
                    service.getEstimatedMinutes()
            );
        }
    }
}