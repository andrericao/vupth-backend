package com.ospaulos.vupth.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * As contas e os totais por conta do Painel (ledger-por-conta, 24/10). So o ADMIN com a permissao FINANCE: traz a tarifa e o
 * que sobra ao app, que nenhum outro perfil ve (DEC-94).
 */
public final class AdminFinanceDtos {

    private AdminFinanceDtos() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Account(
            @JsonProperty("id") UUID id,
            @JsonProperty("key") String key,
            @JsonProperty("name") String name,
            @JsonProperty("provider") String provider,
            @JsonProperty("pix_fee") BigDecimal pixFee,
            @JsonProperty("active") boolean active,
            // so na conta do Asaas: a conexao esta ligada e em que modo (SANDBOX ou REAL)
            @JsonProperty("connected") Boolean connected,
            @JsonProperty("mode") String mode) {}

    public record PixFee(@NotNull @DecimalMin("0.00") @DecimalMax("99.99") @JsonProperty("pix_fee") BigDecimal pixFee) {}

    /**
     * Os totais de uma conta (ou de todas) no periodo, pelo dia em que o pagamento foi confirmado. {@code entries} e o que
     * entrou de fato (o corte e a multa, menos o cupom); {@code fees} as taxas (a tarifa de Pix da conta por Pix, a taxa do
     * preco por cartao); {@code net} = entradas - taxas; {@code balance} (saldo) = o que fica no app = entradas - taxas - parte
     * do profissional - bonus; {@code leftover} (sobra) = a taxa que o preco ja carregava - as taxas.
     */
    public record Totals(
            @JsonProperty("account") String account,
            @JsonProperty("attendances") long attendances,
            @JsonProperty("pix_payments") long pixPayments,
            @JsonProperty("other_payments") long otherPayments,
            @JsonProperty("list_total") BigDecimal listTotal,
            @JsonProperty("coupon_discount") BigDecimal couponDiscount,
            @JsonProperty("entries") BigDecimal entries,
            @JsonProperty("fees") BigDecimal fees,
            @JsonProperty("net") BigDecimal net,
            @JsonProperty("professional_part") BigDecimal professionalPart,
            @JsonProperty("app_part") BigDecimal appPart,
            @JsonProperty("bonus") BigDecimal bonus,
            @JsonProperty("leftover") BigDecimal leftover,
            @JsonProperty("balance") BigDecimal balance,
            @JsonProperty("services_without_split") long servicesWithoutSplit) {}

    public record TotalsReport(
            @JsonProperty("period") AdminMetricsDtos.Window period,
            @JsonProperty("account") String account,
            @JsonProperty("totals") Totals totals,
            // com account=all: cada conta separada (o consolidado e o totals)
            @JsonProperty("by_account") List<Totals> byAccount) {}
}
