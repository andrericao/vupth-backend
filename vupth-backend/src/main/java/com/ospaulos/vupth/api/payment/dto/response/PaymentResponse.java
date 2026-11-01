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
        @JsonProperty("penalty_holder_name") String penaltyHolderName,
        // DEC-85: como o profissional disse que recebeu (PIX, CARTAO, DINHEIRO ou NOTA); nulo = nao informado ou ainda nao pago
        @JsonProperty("payment_method") com.ospaulos.vupth.domain.payment.PaymentMethod paymentMethod,
        // pix-key-no-pagamento: a chave em texto, a MESMA do pix_copy_paste (de quem diz pix_owner); so o cliente a recebe, nulo sem Pix
        @JsonProperty("pix_key") String pixKey,
        // asaas-sandbox-no-fluxo (DEC-119): a cobranca Pix do Asaas, quando existe uma para o atendimento; nulo = vale o Pix do
        // profissional e o "Ja paguei". Cobranca aberta num modo termina nele, mesmo que a conexao mude depois.
        @JsonProperty("gateway") Gateway gateway,
        // bonificacao-diferenca-paga-pelo-app (DEC-127): valor do corte, do profissional, do cliente e o que o app cobre; so o
        // profissional e o cliente o veem, e app_pays some quando e zero
        @JsonProperty("breakdown") com.ospaulos.vupth.domain.payment.DiscountBreakdown breakdown
) {

    /** A cobranca do Asaas: {@code mode} SANDBOX (pagamento de teste, sem dinheiro real) ou REAL; o webhook atualiza o status. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Gateway(
            @JsonProperty("provider") String provider,
            @JsonProperty("mode") String mode,
            @JsonProperty("status") String status,
            @JsonProperty("pix_copy_paste") String pixCopyPaste,
            @JsonProperty("pix_qr_image") String pixQrImage,
            @JsonProperty("invoice_url") String invoiceUrl) {
    }
}
