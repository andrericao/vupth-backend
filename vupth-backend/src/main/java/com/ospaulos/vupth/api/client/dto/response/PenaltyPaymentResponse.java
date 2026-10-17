package com.ospaulos.vupth.api.client.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * O pagamento avulso das multas (pagar-multa-avulso, DEC-83). {@code status}: PENDING (aguardando o Pix), PAID
 * (confirmado pelo app) ou EXPIRED (venceu em 24 h sem confirmacao; as multas voltaram a pendentes).
 * {@code client_id} e {@code client_name} so vao para o painel.
 */
public record PenaltyPaymentResponse(
        @JsonProperty("id") UUID id,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("status") String status,
        @JsonProperty("pix_copy_paste") String pixCopyPaste,
        @JsonProperty("holder_name") String holderName,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("expires_at") OffsetDateTime expiresAt,
        @JsonProperty("paid_at") OffsetDateTime paidAt,
        @JsonProperty("client_id") UUID clientId,
        @JsonProperty("client_name") String clientName
) {}
