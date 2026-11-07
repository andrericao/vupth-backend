package com.ospaulos.vupth.api.referral.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Uma linha do historico de pagamentos (pagamento-ao-profissional). {@code amount} e sempre positivo; {@code type}: {@code PAYMENT} ou
 * {@code REVERSAL}. {@code reversed}: este pagamento ja foi estornado. {@code note}: a nota ou o ID do PIX; no estorno, o motivo.
 */
public record ProfessionalPaymentResponse(
        @JsonProperty("id") UUID id,
        @JsonProperty("professional_id") UUID professionalId,
        @JsonProperty("professional_name") String professionalName,
        @JsonProperty("campaign_id") UUID campaignId,
        @JsonProperty("campaign_name") String campaignName,
        @JsonProperty("type") String type,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("paid_at") OffsetDateTime paidAt,
        @JsonProperty("note") String note,
        @JsonProperty("reversal_of") UUID reversalOf,
        @JsonProperty("reversed") boolean reversed) {
}
