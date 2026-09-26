package com.ospaulos.vupth.api.attendance.dto.response;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public record AttendanceInQueueResponse(
        UUID id,
        UUID establishmentId,
        String establishmentName,
        UUID professionalId,
        String professionalName,
        UUID clientId,
        String clientName,
        List<ServiceItemSummary> services,
        AttendanceStatus status,
        BigDecimal totalValue,
        OffsetDateTime scheduledTime,
        Integer estimatedMinutes,
        //Integer pausedTime,
        Integer durationInMinutes,
        // Item #40 (intencao de fila): isIntent=true enquanto o atendimento ainda cede a vez (intencao
        // nao confirmada); targetTime e o horario-alvo escolhido pelo cliente (null em walk-in normal).
        // Permite ao Front-Partner distinguir intencao vs confirmado e ao Front-Client exibir o estado.
        boolean isIntent,
        OffsetDateTime targetTime,
        // Client 03/07: o horario pedido caia numa refeicao/pausa e o atendimento ficou para depois dela
        // ("voce sera atendido apos o intervalo, por volta de HH:MM" = scheduledTime). So vem true na
        // resposta de quem pediu o horario (entrada com targetTime ou reschedule).
        boolean placedAfterBreak
) {
    public static AttendanceInQueueResponse fromEntity(Attendance attendance) {

        UUID clientId = attendance.getClient() != null ? attendance.getClient().getId() : null;
        String clientName = attendance.getClient() != null ? attendance.getClient().getFirstName() : attendance.getGuestName();


        return new AttendanceInQueueResponse(
                attendance.getId(),
                attendance.getEstablishment().getId(),
                attendance.getEstablishment().getName(),
                attendance.getProfessional().getId(),
                attendance.getProfessional().getName(),
                clientId,
                clientName,
                attendance.getServiceItems().stream()
                        .map(ServiceItemSummary::fromEntity)
                        .collect(Collectors.toList()),
                attendance.getStatus(),
                attendance.getTotalValue(),
                attendance.getScheduledTime(),
                attendance.getEstimatedTotalMinutes(),
                //attendance.getPausedTime
                attendance.getDurationInMinutes(),
                attendance.isIntent(),
                attendance.getTargetTime(),
                attendance.isPlacedAfterBreak()
        );
    }

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