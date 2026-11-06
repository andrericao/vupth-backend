package com.ospaulos.vupth.api.report.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.attendance.model.Attendance;

import java.math.BigDecimal;

/**
 * Composicao do valor de um corte (relatorio-explica-valores, DEC-166): o servico, a taxa do gateway, a taxa do app, a
 * multa e o total que o cliente paga, mais para quem vai cada parte e se as taxas foram de fato {@code cobradas}.
 *
 * <p>Sempre devolve a composicao; quem decide riscar as taxas e a tela. {@code charged = false} no modo teste (Pix direto ao
 * profissional, sem o gateway): as taxas existem no calculo, mas nao foram cobradas. O cliente paga o valor cheio e so
 * ve o total: na visao do cliente as taxas vem {@code null}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValueComposition(
        BigDecimal service,
        @JsonProperty("gateway_fee") BigDecimal gatewayFee,
        @JsonProperty("app_fee") BigDecimal appFee,
        BigDecimal penalty,
        BigDecimal total,
        boolean charged,
        @JsonProperty("goes_to") GoesTo goesTo,
        // chave-profissional-paga-taxa: as taxas em campos separados; fees_active=false = riscadas, o profissional nao paga
        @JsonProperty("app_percent_fee") BigDecimal appPercentFee,
        @JsonProperty("rounding_fee") BigDecimal roundingFee,
        @JsonProperty("gateway_fee_simulated") BigDecimal gatewayFeeSimulated,
        @JsonProperty("fees_active") Boolean feesActive
) {

    /** As taxas separadas do corte; sem elas (servico legado), a composicao fica como esta. */
    public ValueComposition withFees(com.ospaulos.vupth.domain.feature.ProfessionalFeeService.Fees fees) {
        return fees == null ? this : new ValueComposition(service, gatewayFee, appFee, penalty, total, charged, goesTo,
                fees.appPercentFee(), fees.roundingFee(), fees.gatewayFeeSimulated(), fees.active());
    }

    /** Para quem vai cada parte: {@code professional}, {@code gateway} ou {@code app}. */
    public record GoesTo(String service,
                         @JsonProperty("gateway_fee") String gatewayFee,
                         @JsonProperty("app_fee") String appFee,
                         String penalty) {
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** As taxas foram cobradas quando o corte passou pelo gateway; no Pix direto (modo teste) nao. */
    private static boolean charged(Attendance a) {
        return a.getAsaasPaymentId() != null;
    }

    /** Visao do profissional: com as taxas. Servico legado (sem divisao congelada): taxas nulas. */
    public static ValueComposition forProfessional(Attendance a, BigDecimal penalty, boolean splitPix) {
        BigDecimal pen = orZero(penalty);
        boolean legacy = a.getProReceives() == null;
        return new ValueComposition(a.getTotalValue(),
                legacy ? null : orZero(a.getIntermediaryFee()),
                legacy ? null : orZero(a.getPlatformFee()),
                pen, orZero(a.getTotalValue()).add(pen), charged(a),
                new GoesTo("professional", "gateway", "app", splitPix ? "app" : "professional"), null, null, null, null);
    }

    /** Visao do cliente: servico, multa e total; sem as taxas (o cliente paga o valor cheio). */
    public static ValueComposition forClient(Attendance a, BigDecimal penalty) {
        BigDecimal pen = orZero(penalty);
        return new ValueComposition(a.getTotalValue(), null, null, pen,
                orZero(a.getTotalValue()).add(pen), charged(a), null, null, null, null, null);
    }
}
