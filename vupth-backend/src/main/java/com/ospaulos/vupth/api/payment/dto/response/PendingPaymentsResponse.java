package com.ospaulos.vupth.api.payment.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.payment.PaymentStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** GET /clients/me/pending-payments e o corpo do 409 PAYMENT_PENDING (pagamento-pix). */
public record PendingPaymentsResponse(
        @JsonProperty("count") int count,
        @JsonProperty("total") BigDecimal total,
        @JsonProperty("items") List<Item> items
) {
    public record Item(
            @JsonProperty("attendance_id") UUID attendanceId,
            @JsonProperty("status") PaymentStatus status,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("professional_name") String professionalName,
            @JsonProperty("establishment_name") String establishmentName,
            @JsonProperty("finished_at") OffsetDateTime finishedAt
    ) {
    }
}
