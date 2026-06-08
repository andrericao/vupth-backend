package com.ospaulos.vupth.api.establishment.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Representa um profissional com a fila ao vivo embutida.
 * Usado em GET /establishment/id/{id}/professionals-with-queue
 */
public record ProfessionalWithQueueResponse(

        UUID id,
        String name,
        String specialty,

        @JsonProperty("is_available")
        Boolean isAvailable,

        @JsonProperty("current_attendance")
        AttendanceInQueueResponse currentAttendance,

        @JsonProperty("waiting_list")
        List<AttendanceInQueueResponse> waitingList,

        @JsonProperty("queue_count")
        int queueCount,

        @JsonProperty("estimated_wait_minutes")
        int estimatedWaitMinutes,

        @JsonProperty("service_items")
        List<ServiceItemSummary> serviceItems

) {

    public record ServiceItemSummary(
            UUID id,
            ServiceItemType serviceItemType,
            String customName,
            BigDecimal price,
            Integer estimatedMinutes
    ) {
        public static ServiceItemSummary fromEntity(ServiceItem item) {
            return new ServiceItemSummary(
                    item.getId(),
                    item.getServiceItemType(),
                    item.getCustomName(),
                    item.getPrice(),
                    item.getEstimatedMinutes()
            );
        }
    }

    public static ProfessionalWithQueueResponse fromEntity(
            Professional professional,
            Attendance currentAttendance,
            List<Attendance> waitingList) {

        AttendanceInQueueResponse currentDto = currentAttendance != null
                ? AttendanceInQueueResponse.fromEntity(currentAttendance)
                : null;

        List<AttendanceInQueueResponse> waitingDtos = waitingList.stream()
                .map(AttendanceInQueueResponse::fromEntity)
                .toList();

        // Tempo estimado = soma dos estimatedMinutes dos agendados + restante do em progresso
        int estimatedWait = waitingList.stream()
                .mapToInt(a -> a.getEstimatedTotalMinutes() > 0 ? a.getEstimatedTotalMinutes() : 20)
                .sum();
        if (currentAttendance != null) {
            estimatedWait += currentAttendance.getRemainingMinutes();
        }

        List<ServiceItemSummary> serviceItemDtos = professional.getServices().stream()
                .map(ServiceItemSummary::fromEntity)
                .toList();

        return new ProfessionalWithQueueResponse(
                professional.getId(),
                professional.getName(),
                professional.getSpecialty(),
                professional.getIsAvailable(),
                currentDto,
                waitingDtos,
                waitingList.size(),
                estimatedWait,
                serviceItemDtos
        );
    }
}
