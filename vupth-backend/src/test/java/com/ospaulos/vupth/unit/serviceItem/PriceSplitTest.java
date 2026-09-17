package com.ospaulos.vupth.unit.serviceItem;

import com.ospaulos.vupth.domain.serviceItem.exception.InvalidPriceSplitException;
import com.ospaulos.vupth.domain.serviceItem.valueObject.PriceSplit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests — PriceSplit: a divisao do preco do cliente, pela regra do Andre de 16/09.
 *
 * <pre>
 *   taxa          = 6% do valor digitado (base_price)
 *   absorvido     = a parte da taxa que o profissional escolheu pagar (0 a 6% do digitado)
 *   recebe        = digitado - absorvido
 *   preco         = X,90 acima de (digitado + taxa - absorvido)
 *   intermediario = 4% do digitado
 *   app           = preco - recebe - intermediario   (2% + a sobra do arredondamento)
 * </pre>
 * Invariantes: o profissional recebe exatamente o que estipulou; nenhuma parte e negativa; as tres
 * partes somam o preco centavo a centavo. Envolve dinheiro: cada regra tem teste proprio, e uma grade
 * confere o backend contra a MESMA conta do app do parceiro (src/lib/pricing.ts, ecf8069).
 */
@DisplayName("PriceSplit — divisao do preco (regra de 16/09)")
class PriceSplitTest {

    private static BigDecimal brl(String value) {
        return new BigDecimal(value);
    }

    @Nested
    @DisplayName("divisao valida")
    class Valid {

        @ParameterizedTest(name = "digitado {0}, recebe {1}, preco {2} -> pagamento {3}, app {4}")
        @CsvSource({
                "10.00, 10.00, 10.90, 0.40, 0.50",      // o exemplo do Partner (0%)
                "30.00, 30.00, 31.90, 1.20, 0.70",      // 0%
                "30.00, 28.20, 30.90, 1.20, 1.50",      // 100%: absorve os 1,80
                "80.00, 76.40, 81.90, 3.20, 2.30",      // 75%
                "100.00, 100.00, 106.90, 4.00, 2.90",
                "15.00, 15.00, 15.90, 0.60, 0.30",      // 15 x 1,06 = 15,90 exato: nao sobe
                "100.84, 100.84, 106.90, 4.03, 2.03"    // o caso do Partner: recebe 94,3% do preco
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
        }

        @Test
        @DisplayName("todas as partes saem com 2 casas")
        void twoDecimals() {
            PriceSplit split = PriceSplit.of(brl("31.9"), brl("30"), brl("30"));

            assertThat(split.clientPrice().scale()).isEqualTo(2);
            assertThat(split.proReceives().scale()).isEqualTo(2);
            assertThat(split.basePrice().scale()).isEqualTo(2);
            assertThat(split.intermediaryFee().scale()).isEqualTo(2);
            assertThat(split.platformFee().scale()).isEqualTo(2);
        }

        /**
         * A mesma conta do app do parceiro, linha a linha (JavaScript em double). Se o backend
         * recusasse um preco que o app gerou, o cadastro de servico quebraria na mao do profissional.
         */
        private double[] partnerPricing(double desired, int absorptionPercent) {
            double fee = desired * 0.06;
            double proAbsorbs = fee * (absorptionPercent / 100.0);
            double proReceives = round2(desired - proAbsorbs);
            double clientPrice = ceilTo90(desired + fee - proAbsorbs);
            double intermediary = round2(desired * 0.04);
            double app = round2(round2(clientPrice - proReceives) - intermediary);
            return new double[]{clientPrice, proReceives, intermediary, app};
        }

        private double round2(double v) {
            return Math.round(v * 100) / 100.0;
        }

        private double ceilTo90(double v) {
            double clean = Math.round(v * 10000) / 10000.0;
            return round2(Math.ceil(clean - 0.90) + 0.90);
        }

        private int[] optionsFor(double desired) {
            return desired < 50 ? new int[]{0, 50, 100} : new int[]{0, 25, 75, 100};
        }

        @Test
        @DisplayName("grade R$ 5,00 a R$ 500,00 (passo R$ 0,01): tudo que o app do parceiro gera e aceito, com as mesmas partes")
        void matchesPartnerPricingAcrossTheGrid() {
            int checked = 0;
            for (int cents = 500; cents <= 50_000; cents++) {
                double desired = cents / 100.0;
                for (int pct : optionsFor(desired)) {
                    double[] app = partnerPricing(desired, pct);
                    BigDecimal price = BigDecimal.valueOf(app[0]);
                    BigDecimal pro = BigDecimal.valueOf(app[1]);
                    BigDecimal base = BigDecimal.valueOf(desired);

                    PriceSplit split = PriceSplit.of(price, pro, base);

                    assertThat(split.intermediaryFee()).as("4%% de %s a %d%%", base, pct)
                            .isEqualByComparingTo(BigDecimal.valueOf(app[2]));
                    assertThat(split.platformFee()).as("app de %s a %d%%", base, pct)
                            .isEqualByComparingTo(BigDecimal.valueOf(app[3]))
                            .isGreaterThanOrEqualTo(BigDecimal.ZERO);
                    assertThat(split.proReceives().add(split.intermediaryFee()).add(split.platformFee()))
                            .as("soma de %s a %d%%", base, pct)
                            .isEqualByComparingTo(price);
                    checked++;
                }
            }
            assertThat(checked).isGreaterThan(150_000);
        }
    }

    @Nested
    @DisplayName("divisao recusada")
    class Invalid {

        @ParameterizedTest(name = "digitado {0}, recebe {1}, preco {2}")
        @CsvSource({
                "30.00, 30.00, 30.90",   // preco abaixo do devido (31,80)
                "30.00, 30.00, 32.90",   // preco acima do teto do arredondamento (sobe mais de R$ 1)
                "30.00, 30.01, 31.90",   // recebe mais do que digitou (absorvido negativo)
                "30.00, 28.19, 30.90",   // absorve mais que os 6% (1,81 > 1,80)
                "10.00, 10.00, 10.50"    // preco fora do X,90
        })
        @DisplayName("fora da regra")
        void outsideTheRule(String base, String pro, String price) {
            assertThatThrownBy(() -> PriceSplit.of(brl(price), brl(pro), brl(base)))
                    .isInstanceOf(InvalidPriceSplitException.class);
        }

        @Test
        @DisplayName("a regra anterior (4% sobre o preco, recebe <= 94%) nao e mais o criterio")
        void oldRuleIsGone() {
            // Recebe 94,3% do preco — a regra anterior recusava; a de 16/09 aceita.
            assertThatCode(() -> PriceSplit.of(brl("106.90"), brl("100.84"), brl("100.84")))
                    .doesNotThrowAnyException();
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
        }
    }
}
