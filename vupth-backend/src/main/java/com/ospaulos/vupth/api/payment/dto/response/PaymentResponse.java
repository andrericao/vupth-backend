package com.ospaulos.vupth.api.payment.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.payment.PaymentStatus;
import com.ospaulos.vupth.domain.payment.PixKeyType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * GET /attendances/{id}/payment e as acoes (pagamento-pix). {@code amount} = servicos + multas que o
 * atendimento carrega. Sem chave Pix, {@code pix_copy_paste}, {@code holder_name} e {@code key_type}
 * vem nulos e o app mostra so o valor.
 */
public record PaymentResponse(
        @JsonProperty("status") PaymentStatus status,
        @JsonProperty("services_value") BigDecimal servicesValue,
        @JsonProperty("penalties_value") BigDecimal penaltiesValue,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("pix_copy_paste") String pixCopyPaste,
        @JsonProperty("holder_name") String holderName,
        @JsonProperty("key_type") PixKeyType keyType,
        @JsonProperty("claimed_at") OffsetDateTime claimedAt,
        @JsonProperty("confirmed_at") OffsetDateTime confirmedAt,
        // DEC-65 (pix-por-pessoa): de quem e a chave — PROFESSIONAL (quem atendeu) ou OWNER (o dono, a alternativa)
        @JsonProperty("pix_owner") String pixOwner,
        // DEC-83 (dois Pix): o Pix do servico (acima) vai ao profissional; o da multa vai ao app. So o cliente o recebe, e so
        // com multa a pagar e a chave do app configurada.
        @JsonProperty("penalty_pix_copy_paste") String penaltyPixCopyPaste,
        @JsonProperty("penalty_holder_name") String penaltyHolderName
) {
}
