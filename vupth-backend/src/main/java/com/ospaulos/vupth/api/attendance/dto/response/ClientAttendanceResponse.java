package com.ospaulos.vupth.api.attendance.dto.response;

import com.ospaulos.vupth.api.client.dto.response.PenaltyChargeResponse;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * O atendimento como o CLIENTE le o proprio historico: o corte, a multa que veio com ele (se houver), o total e o
 * estado do pagamento. A taxa do gateway e a do app vao embutidas no preco, sem linha propria (DEC-94).
 *
 * <p>Nunca traz o valor que o profissional recebe nem a margem do app. Um DTO proprio, sem campo escondido: o teste de
 * contrato ({@code ReportsByProfileContractTest}) falha se um campo de outro perfil aparecer aqui.</p>
 */
public record ClientAttendanceResponse(
        UUID id,
        UUID clientId,
        String clientName,
        UUID professionalId,
        String professionalName,
        UUID establishmentId,
        String establishmentName,
        String establishmentStatus,
        List<AttendanceInQueueResponse.ServiceItemSummary> services,
        AttendanceStatus status,
        // o corte ("cobrancas do servico"), ja com as taxas embutidas
        BigDecimal totalValue,
        OffsetDateTime scheduledTime,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        Integer durationInMinutes,
        // D4: multas cobradas NESTE atendimento ("cobrancas do app"), cada uma com a origem; separadas do totalValue de proposito
        List<PenaltyChargeResponse> penalties,
        BigDecimal penaltyTotal,
        // o atendimento ja foi avaliado? Decide se o app mostra "Avaliar" (so FINISHED sem nota)
        Boolean rated,
        @com.fasterxml.jackson.annotation.JsonProperty("payment_status")
        com.ospaulos.vupth.domain.payment.PaymentStatus paymentStatus,
        @com.fasterxml.jackson.annotation.JsonProperty("not_realized_reason") String notRealizedReason,
        @com.fasterxml.jackson.annotation.JsonProperty("late_arrival") Boolean lateArrival,
        // cupom-ativado-em-configuracoes: o desconto do cupom; o totalValue ja vem com ele (o que o cliente paga pelo corte)
        @com.fasterxml.jackson.annotation.JsonProperty("discount") BigDecimal discount
) {

    public ClientAttendanceResponse withPenalties(List<PenaltyChargeResponse> charged) {
        BigDecimal total = charged.stream()
                .map(PenaltyChargeResponse::amount)
                .reduce(new BigDecimal("0.00"), BigDecimal::add);
        return new ClientAttendanceResponse(id, clientId, clientName, professionalId, professionalName,
                establishmentId, establishmentName, establishmentStatus, services, status, totalValue, scheduledTime,
                startTime, endTime, durationInMinutes, charged, total, rated, paymentStatus, notRealizedReason,
                lateArrival, discount);
    }

    public ClientAttendanceResponse withRated(boolean isRated) {
        return new ClientAttendanceResponse(id, clientId, clientName, professionalId, professionalName,
                establishmentId, establishmentName, establishmentStatus, services, status, totalValue, scheduledTime,
                startTime, endTime, durationInMinutes, penalties, penaltyTotal, isRated, paymentStatus,
                notRealizedReason, lateArrival, discount);
    }

    public ClientAttendanceResponse withReason(String reason, boolean late) {
        return new ClientAttendanceResponse(id, clientId, clientName, professionalId, professionalName,
                establishmentId, establishmentName, establishmentStatus, services, status, totalValue, scheduledTime,
                startTime, endTime, durationInMinutes, penalties, penaltyTotal, rated, paymentStatus, reason, late, discount);
    }

    public static ClientAttendanceResponse fromEntity(Attendance attendance) {
        UUID clientId = attendance.getClient() != null ? attendance.getClient().getId() : null;
        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : attendance.getGuestName();

        return new ClientAttendanceResponse(
                attendance.getId(),
                clientId,
                clientName,
                attendance.getProfessional().getId(),
                attendance.getProfessional().getName(),
                attendance.getEstablishment().getId(),
                attendance.getEstablishment().getName(),
                StoreAttendanceResponse.establishmentStatusOf(attendance.getEstablishment()),
                attendance.getServiceItems().stream()
                        .map(AttendanceInQueueResponse.ServiceItemSummary::fromEntity)
                        .collect(Collectors.toList()),
                attendance.getStatus(),
                attendance.getAmountDue(),
                attendance.getScheduledTime(),
                attendance.getStartTime(),
                attendance.getEndTime(),
                attendance.getDurationInMinutes(),
                null,
                null,
                null,
                attendance.getEffectivePaymentStatus(),
                null,
                null,
                attendance.getDiscount()
        );
    }
}
