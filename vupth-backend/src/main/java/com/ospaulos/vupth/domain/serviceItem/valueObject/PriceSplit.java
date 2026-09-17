package com.ospaulos.vupth.domain.serviceItem.valueObject;

import com.ospaulos.vupth.domain.serviceItem.exception.InvalidPriceSplitException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Divisao do preco do cliente entre o profissional, a empresa de pagamento e o app — regra do Andre
 * de 16/09 (a mesma do app do parceiro, {@code src/lib/pricing.ts}):
 *
 * <pre>
 *   taxa          = 6% do valor digitado (basePrice)
 *   absorvido     = a parte da taxa que o profissional escolheu pagar: 0 a 6% do digitado
 *   recebe        = digitado - absorvido
 *   preco         = X,90 acima de (digitado + taxa - absorvido)
 *   intermediario = 4% do digitado (centavo meio para cima)
 *   app           = preco - recebe - intermediario   (os 2% + a sobra do arredondamento)
 * </pre>
 *
 * O backend nao recebe as taxas: recebe preco, recebe e digitado, confere a regra e calcula as partes.
 * Invariantes: o profissional recebe exatamente o que estipulou; nenhuma parte e negativa; as tres
 * partes somam o preco centavo a centavo.
 *
 * <p><b>Meio centavo de folga na conferencia do preco.</b> O app arredonda o "recebe" para o centavo e
 * calcula o preco com o absorvido SEM arredondar; aqui so ha o "recebe" gravado. Os dois diferem em no
 * maximo meio centavo, entao o preco e aceito se ficar entre o devido e o devido + R$ 1,00, com essa folga
 * nas duas pontas. O que garante que ninguem sai no prejuizo e a checagem final: o app nunca negativo.
 */
public record PriceSplit(
        BigDecimal clientPrice,
        BigDecimal proReceives,
        BigDecimal basePrice,
        BigDecimal intermediaryFee,
        BigDecimal platformFee
) {

    public static final BigDecimal FEE_RATE = new BigDecimal("0.06");
    public static final BigDecimal INTERMEDIARY_RATE = new BigDecimal("0.04");
    private static final BigDecimal NINETY_CENTS = new BigDecimal("0.90");
    private static final BigDecimal HALF_CENT = new BigDecimal("0.005");
    private static final BigDecimal ROUNDING_CEILING = BigDecimal.ONE.add(HALF_CENT);
    private static final int CENTS = 2;

    public static PriceSplit of(BigDecimal clientPrice, BigDecimal proReceives, BigDecimal basePrice) {
        BigDecimal price = money("preco do cliente", clientPrice);
        BigDecimal pro = money("valor que o profissional recebe", proReceives);
        BigDecimal base = money("valor digitado (base_price)", basePrice);

        if (price.remainder(BigDecimal.ONE).compareTo(NINETY_CENTS) != 0) {
            throw new InvalidPriceSplitException("O preco do cliente deve terminar em ,90 (recebido: " + price + ")");
        }

        BigDecimal fee = base.multiply(FEE_RATE);
        BigDecimal absorbed = base.subtract(pro);
        if (absorbed.signum() < 0) {
            throw new InvalidPriceSplitException(String.format(
                    "O profissional nao pode receber mais do que o valor digitado (digitado %s, recebe %s)", base, pro));
        }
        if (absorbed.compareTo(fee.setScale(CENTS, RoundingMode.HALF_UP)) > 0) {
            throw new InvalidPriceSplitException(String.format(
                    "O profissional so pode absorver ate os 6%% do valor digitado (digitado %s, absorve %s)", base, absorbed));
        }

        BigDecimal owed = base.add(fee).subtract(absorbed);
        BigDecimal roundingUp = price.subtract(owed);
        if (roundingUp.compareTo(HALF_CENT.negate()) < 0 || roundingUp.compareTo(ROUNDING_CEILING) >= 0) {
            throw new InvalidPriceSplitException(String.format(
                    "O preco deve ser o X,90 logo acima de %s (digitado + 6%% - absorvido); recebido %s",
                    owed.setScale(CENTS, RoundingMode.HALF_UP), price));
        }

        BigDecimal intermediary = base.multiply(INTERMEDIARY_RATE).setScale(CENTS, RoundingMode.HALF_UP);
        BigDecimal platform = price.subtract(pro).subtract(intermediary);
        if (platform.signum() < 0) {
            throw new InvalidPriceSplitException(String.format(
                    "A parte do app ficaria negativa (preco %s, recebe %s, pagamento %s)", price, pro, intermediary));
        }
        return new PriceSplit(price, pro, base, intermediary, platform);
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
