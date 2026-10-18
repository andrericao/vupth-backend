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
 *   preco         = o menor X,90 que passa na prova real (comeca no X,90 logo acima de recebe)
 *   intermediario = o maior entre R$ 1,99 (Pix) e R$ 0,49 + 2,99% do PRECO (credito a vista), centavo para cima (DEC-91)
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
    /**
     * O pior caso exato do gateway por preco (DEC-91, o app nunca perde), com as taxas CHEIAS, so a vista:
     * o Pix custa um fixo de R$ 1,99 e o credito a vista R$ 0,49 + 2,99% do preco (o debito custa sempre menos).
     * A taxa cobrada e o maior dos dois ({@link #intermediaryFor}); a virada e em ~R$ 50,17.
     */
    public static final BigDecimal PIX_FEE = new BigDecimal("1.99");
    public static final BigDecimal CARD_FIXED_FEE = new BigDecimal("0.49");
    public static final BigDecimal CARD_RATE = new BigDecimal("0.0299");
    /** A parte do app, sobre o preco cobrado. */
    public static final BigDecimal APP_RATE = new BigDecimal("0.02");
    /** O teto do que o profissional pode absorver, sobre o digitado: as duas taxas juntas. */
    public static final BigDecimal FEE_RATE = INTERMEDIARY_RATE.add(APP_RATE);
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
        return split(price, pro, base, intermediaryFor(price));
    }

    /**
     * A divisao de um servico ja gravado, sem reconferir a regra do X,90 (o preco pode ser da regra de
     * 16/09). {@code null} se o preco nao cobre o profissional e o gateway — o servico volta a "a atualizar".
     * Preco antigo que a taxa nova deixaria sem parte do app mantem a divisao antiga (DEC-91): so os 4% do preco.
     */
    public static PriceSplit read(BigDecimal clientPrice, BigDecimal proReceives, BigDecimal basePrice) {
        if (clientPrice == null || proReceives == null || basePrice == null) {
            return null;
        }
        BigDecimal price = clientPrice.setScale(CENTS, RoundingMode.HALF_UP);
        BigDecimal pro = proReceives.setScale(CENTS, RoundingMode.HALF_UP);
        BigDecimal base = basePrice.setScale(CENTS, RoundingMode.HALF_UP);
        PriceSplit split = split(price, pro, base, intermediaryFor(price));
        if (split.platformFee().signum() >= 0) {
            return split;
        }
        // preco antigo que a taxa nova nao cobre: segue valendo com os 4% do preco (DEC-91)
        PriceSplit legacy = split(price, pro, base, cents(price.multiply(INTERMEDIARY_RATE)));
        return legacy.platformFee().signum() < 0 ? null : legacy;
    }

    /**
     * O preco do cliente para o profissional receber {@code proReceives}: o X,90 logo acima de
     * {@code recebe}, subindo de R$ 1 em R$ 1 ate passar na prova real (a taxa nao e mais um percentual unico,
     * entao nao ha atalho; a grade do PriceSplitTest confere que o X,90 de baixo nunca passa). E a conta que o app do parceiro repete.
     */
    public static BigDecimal clientPriceFor(BigDecimal proReceives) {
        BigDecimal pro = money("valor que o profissional recebe", proReceives);
        BigDecimal price = pro.setScale(0, RoundingMode.FLOOR).add(NINETY_CENTS);
        if (price.compareTo(pro) < 0) {
            price = price.add(BigDecimal.ONE);
        }
        while (!passesProof(price, pro)) {
            price = price.add(BigDecimal.ONE);
        }
        return price;
    }

    /**
     * A taxa do gateway cobrada no preco: o maior entre o Pix cheio e o credito a vista cheio. Arredonda o
     * centavo para cima — a taxa cobrada nunca fica abaixo da real.
     */
    public static BigDecimal intermediaryFor(BigDecimal price) {
        BigDecimal card = CARD_FIXED_FEE.add(price.multiply(CARD_RATE)).setScale(CENTS, RoundingMode.CEILING);
        return PIX_FEE.max(card);
    }

    /** Prova real: depois do gateway e dos 2% do app, sobra ao menos o que o profissional recebe. */
    static boolean passesProof(BigDecimal price, BigDecimal pro) {
        return price.subtract(intermediaryFor(price))
                .subtract(cents(price.multiply(APP_RATE)))
                .compareTo(pro) >= 0;
    }

    private static PriceSplit split(BigDecimal price, BigDecimal pro, BigDecimal base, BigDecimal intermediary) {
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
