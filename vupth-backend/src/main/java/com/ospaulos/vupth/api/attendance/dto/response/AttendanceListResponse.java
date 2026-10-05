package com.ospaulos.vupth.api.attendance.dto.response;

import com.ospaulos.vupth.api.client.dto.response.PenaltyChargeResponse;
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
        // Nome da loja: a tela da fila reconstruida pelo servidor (cliente que entrou por aceite de
        // corte) e o historico do cliente mostram a loja sem outra chamada (campos-do-historico)
        String establishmentName,
        List<AttendanceInQueueResponse.ServiceItemSummary> services,
        AttendanceStatus status,
        BigDecimal totalValue,
        OffsetDateTime scheduledTime,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        Integer durationInMinutes,
        // Divisao do preco congelada na criacao (Receita LIQUIDA). Nulas em atendimento de servico
        // legado ou anterior a 16/09. proReceives + intermediaryFee + platformFee = totalValue.
        BigDecimal proReceives,
        BigDecimal intermediaryFee,
        BigDecimal platformFee,
        // D4: multas cobradas NESTE atendimento ("cobrancas do app"), cada uma com a origem. Separadas do
        // totalValue ("cobrancas do servico") de proposito. Nulas onde nao foram consultadas (listagem
        // da loja); lista vazia = sem multa.
        List<PenaltyChargeResponse> penalties,
        BigDecimal penaltyTotal,
        // Client: o atendimento ja foi avaliado? Decide se o app mostra "Avaliar" (so FINISHED sem nota).
        // Nulo onde nao foi consultado (listagem da loja).
        Boolean rated
) {

    public AttendanceListResponse withPenalties(List<PenaltyChargeResponse> charged) {
        BigDecimal total = charged.stream()
                .map(PenaltyChargeResponse::amount)
                .reduce(new BigDecimal("0.00"), BigDecimal::add);
        return new AttendanceListResponse(id, clientId, clientName, professionalId, professionalName,
                establishmentId, establishmentName, services, status, totalValue, scheduledTime, startTime, endTime,
                durationInMinutes, proReceives, intermediaryFee, platformFee, charged, total, rated);
    }

    public AttendanceListResponse withRated(boolean isRated) {
        return new AttendanceListResponse(id, clientId, clientName, professionalId, professionalName,
                establishmentId, establishmentName, services, status, totalValue, scheduledTime, startTime, endTime,
                durationInMinutes, proReceives, intermediaryFee, platformFee, penalties, penaltyTotal, isRated);
    }

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
                attendance.getEstablishment().getName(),
                attendance.getServiceItems().stream()
                        .map(AttendanceInQueueResponse.ServiceItemSummary::fromEntity)
                        .collect(Collectors.toList()),
                attendance.getStatus(),
                attendance.getTotalValue(),
                attendance.getScheduledTime(),
                attendance.getStartTime(),
                attendance.getEndTime(),
                attendance.getDurationInMinutes(),
                attendance.getProReceives(),
                attendance.getIntermediaryFee(),
                attendance.getPlatformFee(),
                null,
                null,
                null
        );
    }
}
