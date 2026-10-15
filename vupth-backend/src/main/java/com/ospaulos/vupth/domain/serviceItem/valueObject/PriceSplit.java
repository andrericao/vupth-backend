package com.ospaulos.vupth.domain.serviceItem.valueObject;

import com.ospaulos.vupth.domain.serviceItem.exception.InvalidPriceSplitException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Divisao do preco do cliente entre o profissional, a empresa de pagamento e o app — regra do Andre de
 * 15/10 (preco-sobre-o-total, DEC-54), a mesma do app do parceiro ({@code src/lib/pricing.ts}):
 *
 * <pre>
 *   digitado      = o valor que o profissional digitou (basePrice)
 *   absorvido     = a parte da taxa que ele escolheu pagar: 0 a 6% do digitado
 *   recebe        = digitado - absorvido
 *   preco         = o menor X,90 que passa na prova real (comeca no X,90 logo acima de recebe / 0,94)
 *   intermediario = 4% do PRECO (centavo meio para cima) — a taxa do gateway incide sobre o total
 *   app           = preco - recebe - intermediario   (os 2% + a sobra do arredondamento)
 *   prova real    = preco - intermediario - 2% do preco >= recebe
 * </pre>
 *
 * Ate 15/10 o intermediario era 4% do DIGITADO, e quem perdia a diferenca era o app. As taxas ficam em
 * {@link #INTERMEDIARY_RATE} e {@link #APP_RATE}: mudou a do gateway, muda so ali.
 *
 * <p>O backend nao recebe as taxas: recebe preco, recebe e digitado, confere a regra ({@link #of}) e
 * calcula as partes. Invariantes: o profissional recebe exatamente o que estipulou, nunca menos; nenhuma
 * parte e negativa; as tres partes somam o preco centavo a centavo.</p>
 *
 * <p><b>Servico com preco da regra antiga.</b> {@link #of} e a porta do cadastro e da edicao. O que ja
 * esta gravado se le por {@link #read}: o mesmo intermediario sobre o preco e o app com o que sobra, sem
 * reconferir o X,90 — o preco antigo continua valendo ate o profissional edita-lo.</p>
 */
public record PriceSplit(
        BigDecimal clientPrice,
        BigDecimal proReceives,
        BigDecimal basePrice,
        BigDecimal intermediaryFee,
        BigDecimal platformFee
) {

    /** A taxa do gateway (Asaas), sobre o preco cobrado. Unico lugar dela no backend. */
    public static final BigDecimal INTERMEDIARY_RATE = new BigDecimal("0.04");
    /** A parte do app, sobre o preco cobrado. */
    public static final BigDecimal APP_RATE = new BigDecimal("0.02");
    /** O teto do que o profissional pode absorver, sobre o digitado: as duas taxas juntas. */
    public static final BigDecimal FEE_RATE = INTERMEDIARY_RATE.add(APP_RATE);
    private static final BigDecimal NET_RATE = BigDecimal.ONE.subtract(FEE_RATE);
    private static final BigDecimal NINETY_CENTS = new BigDecimal("0.90");
    private static final int CENTS = 2;

    /** Confere o trio contra a regra (cadastro e edicao). */
    public static PriceSplit of(BigDecimal clientPrice, BigDecimal proReceives, BigDecimal basePrice) {
        BigDecimal price = money("preco do cliente", clientPrice);
        BigDecimal pro = money("valor que o profissional recebe", proReceives);
        BigDecimal base = money("valor digitado (base_price)", basePrice);

        if (price.remainder(BigDecimal.ONE).compareTo(NINETY_CENTS) != 0) {
            throw new InvalidPriceSplitException("O preco do cliente deve terminar em ,90 (recebido: " + price + ")");
        }

        BigDecimal absorbed = base.subtract(pro);
        if (absorbed.signum() < 0) {
            throw new InvalidPriceSplitException(String.format(
                    "O profissional nao pode receber mais do que o valor digitado (digitado %s, recebe %s)", base, pro));
        }
        if (absorbed.compareTo(cents(base.multiply(FEE_RATE))) > 0) {
            throw new InvalidPriceSplitException(String.format(
                    "O profissional so pode absorver ate os 6%% do valor digitado (digitado %s, absorve %s)", base, absorbed));
        }

        BigDecimal expected = clientPriceFor(pro);
        if (price.compareTo(expected) != 0) {
            throw new InvalidPriceSplitException(String.format(
                    "O preco deve ser %s (o menor X,90 em que o profissional recebe %s depois das taxas); recebido %s",
                    expected, pro, price));
        }
        return split(price, pro, base);
    }

    /**
     * A divisao de um servico ja gravado, sem reconferir a regra do X,90 (o preco pode ser da regra de
     * 16/09). {@code null} se o preco nao cobre o profissional e o gateway — o servico volta a "a atualizar".
     */
    public static PriceSplit read(BigDecimal clientPrice, BigDecimal proReceives, BigDecimal basePrice) {
        if (clientPrice == null || proReceives == null || basePrice == null) {
            return null;
        }
        PriceSplit split = split(clientPrice.setScale(CENTS, RoundingMode.HALF_UP),
                proReceives.setScale(CENTS, RoundingMode.HALF_UP), basePrice.setScale(CENTS, RoundingMode.HALF_UP));
        return split.platformFee().signum() < 0 ? null : split;
    }

    /**
     * O preco do cliente para o profissional receber {@code proReceives}: o X,90 logo acima de
     * {@code recebe / 0,94}, subindo de R$ 1 em R$ 1 ate passar na prova real (de R$ 0,01 a R$ 500,00 o X,90
     * de baixo nunca passa; a grade do PriceSplitTest confere). E a conta que o app do parceiro repete.
     */
    public static BigDecimal clientPriceFor(BigDecimal proReceives) {
        BigDecimal pro = money("valor que o profissional recebe", proReceives);
        BigDecimal floor = pro.divide(NET_RATE, 6, RoundingMode.HALF_UP);
        BigDecimal price = floor.setScale(0, RoundingMode.FLOOR).add(NINETY_CENTS);
        if (price.compareTo(floor) < 0) {
            price = price.add(BigDecimal.ONE);
        }
        while (!passesProof(price, pro)) {
            price = price.add(BigDecimal.ONE);
        }
        return price;
    }

    /** Prova real: depois do gateway e dos 2% do app, sobra ao menos o que o profissional recebe. */
    static boolean passesProof(BigDecimal price, BigDecimal pro) {
        return price.subtract(cents(price.multiply(INTERMEDIARY_RATE)))
                .subtract(cents(price.multiply(APP_RATE)))
                .compareTo(pro) >= 0;
    }

    private static PriceSplit split(BigDecimal price, BigDecimal pro, BigDecimal base) {
        BigDecimal intermediary = cents(price.multiply(INTERMEDIARY_RATE));
        return new PriceSplit(price, pro, base, intermediary, price.subtract(pro).subtract(intermediary));
    }

    private static BigDecimal cents(BigDecimal value) {
        return value.setScale(CENTS, RoundingMode.HALF_UP);
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
