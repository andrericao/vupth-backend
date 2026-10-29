package com.ospaulos.vupth.domain.payment;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * bonificacao-diferenca-paga-pelo-app (DEC-127): o desconto de QUALQUER promocao (cupom, link, bonificacao, campanha) e pago pelo app.
 * O cliente paga so o corte com desconto (mais a multa, que e do app); o profissional continua recebendo o que estipulou, e o app
 * repassa a diferenca. A conta tem de bater: {@code list_price − discount + penalties = client_pays}, o profissional recebe
 * {@code pro_receives} e o app cobre {@code pro_receives − (list_price − discount)}. {@code app_pays} some quando e zero e
 * {@code discounted} e a flag PAGAMENTO COM DESCONTO. {@code received_amount} e o que o profissional recebeu (padrao: o que o cliente
 * deve pagar) e {@code net_to_professional} = {@code pro_receives − received_amount}: positivo, o app lhe deve ("a receber");
 * negativo, ele deve ao app ("a pagar"). So existe com {@code pro_receives}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiscountBreakdown(
        @JsonProperty("discounted") boolean discounted,
        @JsonProperty("list_price") BigDecimal listPrice,
        @JsonProperty("pro_receives") BigDecimal proReceives,
        @JsonProperty("discount") BigDecimal discount,
        @JsonProperty("penalties") BigDecimal penalties,
        @JsonProperty("client_pays") BigDecimal clientPays,
        @JsonProperty("app_pays") BigDecimal appPays,
        @JsonProperty("received_amount") BigDecimal receivedAmount,
        @JsonProperty("net_to_professional") BigDecimal netToProfessional) {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    /**
     * @param proReceives o que o profissional estipulou; nulo = servico legado, sem divisao (nao ha o que o app repassar)
     * @param penalties   a multa que o atendimento carrega (vai ao app, nao entra no repasse)
     */
    public static DiscountBreakdown of(BigDecimal listPrice, BigDecimal proReceives, BigDecimal discount, BigDecimal penalties) {
        return of(listPrice, proReceives, discount, penalties, null);
    }

    public static DiscountBreakdown of(BigDecimal listPrice, BigDecimal proReceives, BigDecimal discount, BigDecimal penalties,
                                       BigDecimal receivedAmount) {
        BigDecimal list = scaled(listPrice);
        BigDecimal off = scaled(discount).min(list);
        BigDecimal fine = scaled(penalties);
        BigDecimal service = list.subtract(off);
        BigDecimal appPays = null;
        if (off.signum() > 0 && proReceives != null) {
            BigDecimal diff = scaled(proReceives).subtract(service);
            appPays = diff.signum() > 0 ? diff : null;
        }
        BigDecimal received = receivedAmount != null ? scaled(receivedAmount) : service;
        BigDecimal net = proReceives == null ? null : scaled(proReceives).subtract(received);
        return new DiscountBreakdown(off.signum() > 0, list, proReceives == null ? null : scaled(proReceives), off, fine,
                service.add(fine), appPays, proReceives == null ? null : received, net);
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
