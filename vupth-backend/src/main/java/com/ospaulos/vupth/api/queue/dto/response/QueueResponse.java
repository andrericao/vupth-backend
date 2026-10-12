package com.ospaulos.vupth.api.queue.dto.response;

import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInProgressResponse;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.payment.PaymentStatus;

import java.util.List;
import java.util.UUID;

public record QueueResponse(
        UUID professionalId,
        String professionalName,
        UUID establishmentId,
        String establishmentName,
        int size,
        List<AttendanceInQueueResponse> attendancesInQueue,
        int totalEstimatedMinutes,
        int totalElapsedMinutes,
        int totalRemainingMinutes,
        //int pausedTime,
        AttendanceInProgressResponse inAttendance,
        // finish-com-payment-status (S47, 11/10): so a resposta do PATCH /attendances/{id}/finish traz o
        // pagamento do atendimento que acabou de terminar; nas outras rotas fica nulo e nao sai no JSON.
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("payment_status")
        PaymentStatus paymentStatus
) {

    public QueueResponse(UUID professionalId, String professionalName, UUID establishmentId,
                         String establishmentName, int size, List<AttendanceInQueueResponse> attendancesInQueue,
                         int totalEstimatedMinutes, int totalElapsedMinutes, int totalRemainingMinutes,
                         AttendanceInProgressResponse inAttendance) {
        this(professionalId, professionalName, establishmentId, establishmentName, size, attendancesInQueue,
                totalEstimatedMinutes, totalElapsedMinutes, totalRemainingMinutes, inAttendance, null);
    }

    /** A mesma fila, com o pagamento do atendimento finalizado (finish-com-payment-status). */
    public QueueResponse withPaymentStatus(PaymentStatus status) {
        return new QueueResponse(professionalId, professionalName, establishmentId, establishmentName, size,
                attendancesInQueue, totalEstimatedMinutes, totalElapsedMinutes, totalRemainingMinutes,
                inAttendance, status);
    }
}