package com.ospaulos.vupth.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** As telas do ADMIN dos testes de pagamento no sandbox do Asaas (testes-asaas-sandbox, fatia 1). */
public final class AsaasSandboxDtos {

    private AsaasSandboxDtos() {}

    public record ChargeRequest(
            @NotNull @DecimalMin("5.00") @JsonProperty("amount") BigDecimal amount,
            /** PIX (padrao), BOLETO, CREDIT_CARD ou UNDEFINED. */
            @JsonProperty("billing_type") String billingType,
            /** O atendimento que a cobranca representa (para o relatorio conferir com o que o app calculou). */
            @JsonProperty("attendance_id") java.util.UUID attendanceId,
            @JsonProperty("cpf_cnpj") String cpfCnpj) {}

    public record ChargeResponse(
            @JsonProperty("payment_id") String paymentId,
            @JsonProperty("status") String status,
            @JsonProperty("value") BigDecimal value,
            @JsonProperty("billing_type") String billingType,
            @JsonProperty("invoice_url") String invoiceUrl,
            @JsonProperty("external_reference") String externalReference,
            @JsonProperty("next_step") String nextStep) {}

    /** Para onde vai cada centavo de uma cobranca. Os campos do app vem do atendimento, os do Asaas, dos avisos. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Line(
            @JsonProperty("payment_id") String paymentId,
            @JsonProperty("attendance_id") String attendanceId,
            @JsonProperty("billing_type") String billingType,
            @JsonProperty("status") String status,
            @JsonProperty("events") List<String> events,
            @JsonProperty("client_paid") BigDecimal clientPaid,
            @JsonProperty("asaas_net") BigDecimal asaasNet,
            @JsonProperty("asaas_fee_real") BigDecimal asaasFeeReal,
            @JsonProperty("professional_planned") BigDecimal professionalPlanned,
            @JsonProperty("app_two_percent") BigDecimal appTwoPercent,
            @JsonProperty("rounding") BigDecimal rounding,
            @JsonProperty("penalty") BigDecimal penalty,
            @JsonProperty("fee_charged_in_price") BigDecimal feeChargedInPrice,
            @JsonProperty("fee_leftover") BigDecimal feeLeftover,
            @JsonProperty("app_net") BigDecimal appNet,
            @JsonProperty("note") String note,
            // asaas-sandbox-no-fluxo (DEC-119): o que o cupom e a bonificacao fazem com o valor cobrado
            @JsonProperty("list_price") BigDecimal listPrice,
            @JsonProperty("coupon_discount") BigDecimal couponDiscount,
            @JsonProperty("bonus") BigDecimal bonus,
            @JsonProperty("difference_cents") Long differenceCents) {}

    public record Report(
            @JsonProperty("period") AdminMetricsDtos.Window period,
            @JsonProperty("payments") List<Line> payments,
            @JsonProperty("totals") Totals totals,
            @JsonProperty("not_measured") List<String> notMeasured) {}

    public record Totals(
            @JsonProperty("client_paid") BigDecimal clientPaid,
            @JsonProperty("asaas_fee_real") BigDecimal asaasFeeReal,
            @JsonProperty("professional_planned") BigDecimal professionalPlanned,
            @JsonProperty("app_net") BigDecimal appNet,
            @JsonProperty("generated_at") OffsetDateTime generatedAt) {}
}
