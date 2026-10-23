package com.ospaulos.vupth.api.attendance.dto.response;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse.ServiceItemSummary;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resposta da intencao de atendimento (backlog #4 / status INTENTION). Alem dos dados do
 * atendimento, devolve a {@code estimatedStartTime}: a estimativa de quando o cliente seria
 * atendido, ja considerando as OUTRAS intencoes do profissional (encaixe cronologico via
 * {@code QueueUtils.assignAgendaSlot}) — sem, no entanto, reservar posicao na fila viva (Modelo A).
 * O {@code desiredTime} e o horario que o cliente pediu; {@code estimatedStartTime} e o que o
 * sistema projeta.
 */
public record IntentionResponse(
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
        Integer estimatedMinutes,
        OffsetDateTime desiredTime,
        OffsetDateTime estimatedStartTime,
        // cupom-ativado-em-configuracoes: o desconto do cupom; o totalValue ja vem com ele
        @com.fasterxml.jackson.annotation.JsonProperty("discount") BigDecimal discount
) {
    public static IntentionResponse fromEntity(Attendance attendance, OffsetDateTime estimatedStartTime) {
        UUID clientId = attendance.getClient() != null ? attendance.getClient().getId() : null;
        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : attendance.getGuestName();

        return new IntentionResponse(
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
                attendance.getAmountDue(),
                attendance.getEstimatedTotalMinutes(),
                attendance.getDesiredTime(),
                estimatedStartTime,
                attendance.getDiscount()
        );
    }
}
