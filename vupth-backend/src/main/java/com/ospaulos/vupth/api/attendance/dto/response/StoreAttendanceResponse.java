package com.ospaulos.vupth.api.attendance.dto.response;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * O atendimento como a LOJA o le (profissional e dono): o que recebe e as taxas embutidas no preco que o cliente ve
 * (a do gateway e a do app, mais o arredondamento), a mesma composicao do cadastro do servico (DEC-94).
 *
 * <p>Nunca traz a multa nem o lucro do app, e do cliente so o nome e o necessario ao atendimento (sem e-mail, telefone
 * nem historico de multas). Um DTO proprio, sem campo escondido: o teste de contrato
 * ({@code ReportsByProfileContractTest}) falha se um campo de outro perfil aparecer aqui.</p>
 */
public record StoreAttendanceResponse(
        UUID id,
        UUID clientId,
        String clientName,
        UUID professionalId,
        String professionalName,
        UUID establishmentId,
        String establishmentName,
        // desativar-conta (6): ACTIVE | DEACTIVATED | REMOVED
        String establishmentStatus,
        List<AttendanceInQueueResponse.ServiceItemSummary> services,
        AttendanceStatus status,
        BigDecimal totalValue,
        OffsetDateTime scheduledTime,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        Integer durationInMinutes,
        // Divisao do preco congelada na criacao (Receita LIQUIDA). Nulas em servico legado ou anterior a 16/09.
        // proReceives + intermediaryFee + platformFee = totalValue.
        BigDecimal proReceives,
        BigDecimal intermediaryFee,
        BigDecimal platformFee,
        @com.fasterxml.jackson.annotation.JsonProperty("payment_status")
        com.ospaulos.vupth.domain.payment.PaymentStatus paymentStatus,
        // relatorio-sem-multa (DEC-83): CLIENT_CANCELLED, PROFESSIONAL_REMOVED ou REMOVED_FOR_LATENESS; sem valor
        @com.fasterxml.jackson.annotation.JsonProperty("not_realized_reason") String notRealizedReason,
        @com.fasterxml.jackson.annotation.JsonProperty("late_arrival") Boolean lateArrival
) {

    public StoreAttendanceResponse withReason(String reason, boolean late) {
        return new StoreAttendanceResponse(id, clientId, clientName, professionalId, professionalName,
                establishmentId, establishmentName, establishmentStatus, services, status, totalValue, scheduledTime,
                startTime, endTime, durationInMinutes, proReceives, intermediaryFee, platformFee, paymentStatus,
                reason, late);
    }

    public static StoreAttendanceResponse fromEntity(Attendance attendance) {
        UUID clientId = attendance.getClient() != null ? attendance.getClient().getId() : null;
        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : attendance.getGuestName();

        return new StoreAttendanceResponse(
                attendance.getId(),
                clientId,
                clientName,
                attendance.getProfessional().getId(),
                attendance.getProfessional().getName(),
                attendance.getEstablishment().getId(),
                attendance.getEstablishment().getName(),
                establishmentStatusOf(attendance.getEstablishment()),
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
                attendance.getEffectivePaymentStatus(),
                null,
                null
        );
    }

    /** O que o historico precisa saber da loja: ativa, desativada pelo dono ou removida. */
    static String establishmentStatusOf(com.ospaulos.vupth.domain.establishment.model.Establishment establishment) {
        var status = establishment.getStatus();
        if (status == com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus.DEACTIVATED) {
            return "DEACTIVATED";
        }
        if (status == com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus.REMOVED) {
            return "REMOVED";
        }
        return "ACTIVE";
    }
}
