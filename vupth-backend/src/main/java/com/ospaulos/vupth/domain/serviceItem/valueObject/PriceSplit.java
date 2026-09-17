package com.ospaulos.vupth.domain.serviceItem.valueObject;

import com.ospaulos.vupth.domain.serviceItem.exception.InvalidPriceSplitException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Divisao do preco do cliente entre o profissional, a empresa de pagamento e o app.
 *
 * <p>A taxa e 6% do preco do cliente: 4% da empresa de pagamento (sobre o valor transacionado) e
 * 2% do app. O preco do cliente e arredondado para X,90 PARA CIMA, entao a taxa efetiva e >= 6% e
 * a sobra do arredondamento e do app. Invariantes (Andre, 16/09):
 * <ol>
 *   <li>o profissional recebe EXATAMENTE o que estipulou;</li>
 *   <li>o app fica com os 2% mais a sobra — o que exige {@code proReceives <= 94%} do preco;</li>
 *   <li>nenhuma parte e negativa;</li>
 *   <li>as tres partes somam o preco do cliente, centavo a centavo (o app fica com o resto).</li>
 * </ol>
 * O centavo dos 4% arredonda meio para cima.
 */
public record PriceSplit(
        BigDecimal clientPrice,
        BigDecimal proReceives,
        BigDecimal intermediaryFee,
        BigDecimal platformFee
) {

    public static final BigDecimal FEE_RATE = new BigDecimal("0.06");
    public static final BigDecimal INTERMEDIARY_RATE = new BigDecimal("0.04");
    private static final BigDecimal PRO_MAX_SHARE = BigDecimal.ONE.subtract(FEE_RATE);
    private static final BigDecimal NINETY_CENTS = new BigDecimal("0.90");
    private static final int CENTS = 2;

    public static PriceSplit of(BigDecimal clientPrice, BigDecimal proReceives) {
        BigDecimal client = money("preco do cliente", clientPrice);
        BigDecimal pro = money("valor que o profissional recebe", proReceives);

        if (client.remainder(BigDecimal.ONE).compareTo(NINETY_CENTS) != 0) {
            throw new InvalidPriceSplitException(
                    "O preco do cliente deve terminar em ,90 (recebido: " + client + ")");
        }
        if (pro.compareTo(client.multiply(PRO_MAX_SHARE)) > 0) {
            throw new InvalidPriceSplitException(String.format(
                    "O profissional pode receber no maximo 94%% do preco do cliente — a taxa de 6%% "
                            + "nao cabe (preco %s, recebe %s)", client, pro));
        }

        BigDecimal intermediary = client.multiply(INTERMEDIARY_RATE).setScale(CENTS, RoundingMode.HALF_UP);
        BigDecimal platform = client.subtract(pro).subtract(intermediary);
        return new PriceSplit(client, pro, intermediary, platform);
    }

    private static BigDecimal money(String field, BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new InvalidPriceSplitException("O " + field + " deve ser maior que zero");
        }
        if (value.stripTrailingZeros().scale() > CENTS) {
            throw new InvalidPriceSplitException("O " + field + " tem mais de 2 casas decimais: " + value);
        }
        return value.setScale(CENTS, RoundingMode.UNNECESSARY);
    }
}
