package com.ospaulos.vupth.web.rest.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.payment.RefundReason;
import com.ospaulos.vupth.domain.payment.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * estorno-asaas-casos (DEC-133/137): o ADMIN estorna, pelo Painel, a cobranca ja paga do Asaas de um atendimento. So total. Como
 * toda {@code /admin/**}, so o ROLE_ADMIN chega aqui.
 */
@RestController
@RequiredArgsConstructor
public class AdminRefundController {

    private final PaymentService paymentService;

    public record RefundRequest(@NotNull @JsonProperty("reason") RefundReason reason) {}

    public record RefundResponse(@JsonProperty("attendance_id") UUID attendanceId,
                                 @JsonProperty("refunded_at") OffsetDateTime refundedAt,
                                 @JsonProperty("reason") RefundReason reason,
                                 @JsonProperty("asaas_status") String asaasStatus,
                                 @JsonProperty("penalties_released") int penaltiesReleased) {}

    @PostMapping("/admin/attendances/{id}/refund")
    public ResponseEntity<RefundResponse> refund(@PathVariable UUID id, @RequestBody @Valid RefundRequest body) {
        var r = paymentService.refund(id, body.reason());
        return ResponseEntity.ok(new RefundResponse(r.attendanceId(), r.refundedAt(), r.reason(), r.asaasStatus(), r.penaltiesReleased()));
    }
}
