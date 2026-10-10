package com.ospaulos.vupth.api.payment.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.payment.PaymentStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * GET /professional/me/pending-payments (pagamento-pix, adendo 1): o dot do profissional.
 * {@code awaiting_count} = os "Ja paguei" esperando o "Recebi".
 */
public record ProfessionalPendingPaymentsResponse(
        @JsonProperty("awaiting_count") int awaitingCount,
        @JsonProperty("items") List<Item> items
) {
    public record Item(
            @JsonProperty("attendance_id") UUID attendanceId,
            @JsonProperty("status") PaymentStatus status,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("client_name") String clientName,
            @JsonProperty("finished_at") OffsetDateTime finishedAt,
            @JsonProperty("claimed_at") OffsetDateTime claimedAt
    ) {
    }
}
