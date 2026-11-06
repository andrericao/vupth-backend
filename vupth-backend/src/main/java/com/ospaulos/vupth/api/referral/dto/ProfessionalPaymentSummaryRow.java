package com.ospaulos.vupth.api.referral.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A pagar x pago de uma pessoa (pagamento-ao-profissional). {@code to_pay}: o que esta liberado e aberto agora, sem periodo. {@code paid}:
 * os pagamentos menos os estornos do periodo. {@code last_paid_at}: o ultimo pagamento do periodo.
 */
public record ProfessionalPaymentSummaryRow(
        @JsonProperty("professional_id") UUID professionalId,
        @JsonProperty("professional_name") String professionalName,
        @JsonProperty("to_pay") BigDecimal toPay,
        @JsonProperty("paid") BigDecimal paid,
        @JsonProperty("last_paid_at") OffsetDateTime lastPaidAt) {
}
