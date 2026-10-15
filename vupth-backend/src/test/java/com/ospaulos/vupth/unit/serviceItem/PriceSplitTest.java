package com.ospaulos.vupth.unit.serviceItem;

import com.ospaulos.vupth.domain.serviceItem.exception.InvalidPriceSplitException;
import com.ospaulos.vupth.domain.serviceItem.valueObject.PriceSplit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests — PriceSplit: a divisao do preco do cliente, pela regra do Andre de 15/10
 * (preco-sobre-o-total, DEC-54).
 *
 * <pre>
 *   recebe        = digitado - absorvido (absorvido de 0 a 6% do digitado)
 *   preco         = o menor X,90 que passa na prova real (a partir de recebe / 0,94)
 *   intermediario = 4% do PRECO
 *   app           = preco - recebe - intermediario   (2% + a sobra do arredondamento)
 *   prova real    = preco - 4% - 2% (do preco) >= recebe
 * </pre>
 * Envolve dinheiro: cada regra tem teste proprio, e uma grade varre os precos de R$ 5 a R$ 500.
 */
@DisplayName("PriceSplit — divisao do preco (regra de 15/10)")
class PriceSplitTest {

    private static BigDecimal brl(String value) {
        return new BigDecimal(value);
    }

    private static BigDecimal cents(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    @Nested
    @DisplayName("divisao valida")
    class Valid {

        @ParameterizedTest(name = "digitado {0}, recebe {1}, preco {2} -> pagamento {3}, app {4}")
        @CsvSource({
                "10.00, 10.00, 10.90, 0.44, 0.46",
                "30.00, 30.00, 32.90, 1.32, 1.58",      // 31,91 sobe para 32,90 (hoje era 31,90)
                "30.00, 28.20, 30.90, 1.24, 1.46",      // absorve os 1,80
                "50.00, 50.00, 53.90, 2.16, 1.74",      // o exemplo do Andre: 4% de 53,90, nao de 50
                "80.00, 76.40, 81.90, 3.28, 2.22",
                "80.00, 80.00, 85.90, 3.44, 2.46",
                "100.00, 100.00, 106.90, 4.28, 2.62",
                "100.84, 100.84, 107.90, 4.32, 2.74"
        })
        @DisplayName("valores concretos")
        void concreteValues(String base, String pro, String price, String intermediary, String app) {
            PriceSplit split = PriceSplit.of(brl(price), brl(pro), brl(base));

            assertThat(split.basePrice()).isEqualByComparingTo(base);
            assertThat(split.proReceives()).isEqualByComparingTo(pro);
            assertThat(split.intermediaryFee()).isEqualByComparingTo(intermediary);
            assertThat(split.platformFee()).isEqualByComparingTo(app);
            assertThat(split.proReceives().add(split.intermediaryFee()).add(split.platformFee()))
                    .isEqualByComparingTo(price);
            assertThat(PriceSplit.clientPriceFor(brl(pro))).isEqualByComparingTo(price);
        }

        @Test
        @DisplayName("todas as partes saem com 2 casas")
        void twoDecimals() {
            PriceSplit split = PriceSplit.of(brl("32.9"), brl("30"), brl("30"));

            assertThat(split.clientPrice().scale()).isEqualTo(2);
            assertThat(split.proReceives().scale()).isEqualTo(2);
            assertThat(split.basePrice().scale()).isEqualTo(2);
            assertThat(split.intermediaryFee().scale()).isEqualTo(2);
            assertThat(split.platformFee().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("grade R$ 5,00 a R$ 500,00 (passo R$ 0,01): prova real, o menor X,90, 4% do preco e soma exata")
        void goldenRuleAcrossTheGrid() {
            int checked = 0;
            for (int c = 500; c <= 50_000; c++) {
                BigDecimal base = BigDecimal.valueOf(c, 2);
                BigDecimal maxAbsorbed = cents(base.multiply(PriceSplit.FEE_RATE));
                for (BigDecimal absorbed : new BigDecimal[]{BigDecimal.ZERO.setScale(2), cents(maxAbsorbed.divide(BigDecimal.TWO)), maxAbsorbed}) {
                    BigDecimal pro = base.subtract(absorbed);
                    BigDecimal price = PriceSplit.clientPriceFor(pro);

                    PriceSplit split = PriceSplit.of(price, pro, base);

                    BigDecimal gateway = cents(price.multiply(PriceSplit.INTERMEDIARY_RATE));
                    BigDecimal appShare = cents(price.multiply(PriceSplit.APP_RATE));
                    assertThat(split.intermediaryFee()).as("4%% do preco %s", price).isEqualByComparingTo(gateway);
                    assertThat(price.subtract(gateway).subtract(appShare))
                            .as("prova real: %s cobre o recebe %s", price, pro)
                            .isGreaterThanOrEqualTo(pro);
                    assertThat(split.platformFee()).as("app de %s", price).isGreaterThanOrEqualTo(appShare);
                    assertThat(split.proReceives().add(split.intermediaryFee()).add(split.platformFee()))
                            .as("soma de %s", price).isEqualByComparingTo(price);
                    assertThat(price.remainder(BigDecimal.ONE)).isEqualByComparingTo("0.90");
                    BigDecimal cheaper = price.subtract(BigDecimal.ONE);
                    assertThat(cheaper.subtract(cents(cheaper.multiply(PriceSplit.INTERMEDIARY_RATE)))
                            .subtract(cents(cheaper.multiply(PriceSplit.APP_RATE))))
                            .as("o X,90 abaixo de %s nao cobriria %s", price, pro)
                            .isLessThan(pro);
                    checked++;
                }
            }
            assertThat(checked).isGreaterThan(140_000);
        }
    }

    @Nested
    @DisplayName("divisao recusada")
    class Invalid {

        @ParameterizedTest(name = "digitado {0}, recebe {1}, preco {2}")
        @CsvSource({
                "30.00, 30.00, 31.90",   // a regra de 16/09: nao cobre os 30 depois das taxas
                "30.00, 30.00, 33.90",   // acima do menor X,90
                "30.00, 30.01, 32.90",   // recebe mais do que digitou (absorvido negativo)
                "30.00, 28.19, 30.90",   // absorve mais que os 6% (1,81 > 1,80)
                "10.00, 10.00, 10.50"    // preco fora do X,90
        })
        @DisplayName("fora da regra")
        void outsideTheRule(String base, String pro, String price) {
            assertThatThrownBy(() -> PriceSplit.of(brl(price), brl(pro), brl(base)))
                    .isInstanceOf(InvalidPriceSplitException.class);
        }

        @Test
        @DisplayName("valores ausentes, zero, negativos ou com mais de 2 casas")
        void notMoney() {
            assertThatThrownBy(() -> PriceSplit.of(null, brl("10"), brl("10"))).isInstanceOf(InvalidPriceSplitException.class);
            assertThatThrownBy(() -> PriceSplit.of(brl("10.90"), null, brl("10"))).isInstanceOf(InvalidPriceSplitException.class);
            assertThatThrownBy(() -> PriceSplit.of(brl("10.90"), brl("10"), null)).isInstanceOf(InvalidPriceSplitException.class);
            assertThatThrownBy(() -> PriceSplit.of(brl("10.90"), brl("0"), brl("10"))).isInstanceOf(InvalidPriceSplitException.class);
            assertThatThrownBy(() -> PriceSplit.of(brl("10.90"), brl("10"), brl("-10"))).isInstanceOf(InvalidPriceSplitException.class);
            assertThatThrownBy(() -> PriceSplit.of(brl("10.90"), brl("10.001"), brl("10"))).isInstanceOf(InvalidPriceSplitException.class);
            assertThatThrownBy(() -> PriceSplit.of(brl("10.90"), brl("10"), brl("10.001"))).isInstanceOf(InvalidPriceSplitException.class);
            assertThatThrownBy(() -> PriceSplit.clientPriceFor(brl("0"))).isInstanceOf(InvalidPriceSplitException.class);
        }
    }

    @Nested
    @DisplayName("leitura do que ja esta gravado")
    class Read {

        @Test
        @DisplayName("preco da regra de 16/09 continua lido, com o gateway sobre o preco")
        void oldRulePrice() {
            PriceSplit split = PriceSplit.read(brl("31.90"), brl("30.00"), brl("30.00"));

            assertThat(split.intermediaryFee()).isEqualByComparingTo("1.28");
            assertThat(split.platformFee()).isEqualByComparingTo("0.62");
        }

        @Test
        @DisplayName("preco que nao cobre o profissional e o gateway -> null (a atualizar)")
        void doesNotCover() {
            assertThat(PriceSplit.read(brl("30.90"), brl("30.00"), brl("30.00"))).isNull();
        }

        @Test
        @DisplayName("qualquer valor ausente -> null")
        void missing() {
            assertThat(PriceSplit.read(null, brl("30"), brl("30"))).isNull();
            assertThat(PriceSplit.read(brl("32.90"), null, brl("30"))).isNull();
            assertThat(PriceSplit.read(brl("32.90"), brl("30"), null)).isNull();
        }
    }
}
