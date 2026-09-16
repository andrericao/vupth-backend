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
 * Unit tests — PriceSplit: a divisao do preco do cliente entre profissional, empresa de pagamento
 * e app. Envolve dinheiro: cada invariante tem teste proprio.
 *
 * Invariantes (Andre, 16/09):
 * 1. o profissional recebe EXATAMENTE o que estipulou — nunca menos;
 * 2. a taxa e 6% = 4% da empresa de pagamento + 2% do app; o app fica com os 2% MAIS a sobra do
 *    arredondamento para X,90;
 * 3. nenhuma parte e negativa;
 * 4. as tres partes somam o preco do cliente, centavo a centavo.
 */
@DisplayName("PriceSplit — divisao do preco do cliente")
class PriceSplitTest {

    private static BigDecimal brl(String value) {
        return new BigDecimal(value);
    }

    @Nested
    @DisplayName("divisao valida")
    class Valid {

        @ParameterizedTest(name = "cliente {0}, recebe {1} -> pagamento {2}, app {3}")
        @CsvSource({
                "32.90, 30.00, 1.32, 1.58",
                "11.90, 10.50, 0.48, 0.92",
                "44.90, 42.13, 1.80, 0.97",
                "81.90, 76.17, 3.28, 2.45",
                "106.90, 100.00, 4.28, 2.62",
                "5.90, 4.84, 0.24, 0.82"
        })
        @DisplayName("valores concretos (os da proposta ao Partner, 16/09)")
        void concreteValues(String client, String pro, String intermediary, String platform) {
            PriceSplit split = PriceSplit.of(brl(client), brl(pro));

            assertThat(split.proReceives()).isEqualByComparingTo(pro);
            assertThat(split.intermediaryFee()).isEqualByComparingTo(intermediary);
            assertThat(split.platformFee()).isEqualByComparingTo(platform);
        }

        @Test
        @DisplayName("invariante 1: o profissional recebe exatamente o que estipulou")
        void proReceivesIsKeptExactly() {
            PriceSplit split = PriceSplit.of(brl("32.90"), brl("30.00"));

            assertThat(split.proReceives()).isEqualByComparingTo("30.00");
            assertThat(split.clientPrice()).isEqualByComparingTo("32.90");
        }

        @Test
        @DisplayName("4% com centavo quebrado: arredonda meio para cima, e a sobra fica com o app")
        void intermediaryRoundsHalfUp() {
            // 31.90 x 4% = 1.276 -> 1.28
            PriceSplit split = PriceSplit.of(brl("31.90"), brl("29.90"));

            assertThat(split.intermediaryFee()).isEqualByComparingTo("1.28");
            assertThat(split.platformFee()).isEqualByComparingTo("0.72");
        }

        @Test
        @DisplayName("limite exato: receber 94% do preco e aceito")
        void exactlyNinetyFourPercentIsAccepted() {
            // 15.90 x 0.94 = 14.946 -> 14.94 cabe; 14.95 nao
            PriceSplit split = PriceSplit.of(brl("15.90"), brl("14.94"));

            assertThat(split.platformFee()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("todas as partes saem com 2 casas")
        void allPartsHaveTwoDecimals() {
            PriceSplit split = PriceSplit.of(brl("32.9"), brl("30"));

            assertThat(split.clientPrice().scale()).isEqualTo(2);
            assertThat(split.proReceives().scale()).isEqualTo(2);
            assertThat(split.intermediaryFee().scale()).isEqualTo(2);
            assertThat(split.platformFee().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("invariantes 2, 3 e 4 em todo preco X,90 de R$ 4,90 a R$ 500,90 com o maior recebe permitido")
        void invariantsHoldAcrossTheGrid() {
            for (int reais = 4; reais <= 500; reais++) {
                BigDecimal client = BigDecimal.valueOf(reais).add(brl("0.90"));
                BigDecimal maxPro = client.multiply(brl("0.94")).setScale(2, RoundingMode.DOWN);

                for (BigDecimal pro : new BigDecimal[]{maxPro, maxPro.subtract(brl("0.01")), brl("1.00")}) {
                    PriceSplit split = PriceSplit.of(client, pro);

                    BigDecimal sum = split.proReceives().add(split.intermediaryFee()).add(split.platformFee());
                    assertThat(sum).as("soma em %s/%s", client, pro).isEqualByComparingTo(client);
                    assertThat(split.intermediaryFee()).as("4%% em %s", client)
                            .isEqualByComparingTo(client.multiply(brl("0.04")).setScale(2, RoundingMode.HALF_UP));
                    assertThat(split.platformFee()).as("app nunca negativo em %s/%s", client, pro)
                            .isGreaterThanOrEqualTo(BigDecimal.ZERO);
                    assertThat(split.platformFee()).as("app >= 2%% em %s/%s", client, pro)
                            .isGreaterThanOrEqualTo(client.multiply(brl("0.02")).setScale(2, RoundingMode.DOWN));
                }
            }
        }
    }

    @Nested
    @DisplayName("divisao recusada")
    class Invalid {

        @ParameterizedTest(name = "cliente {0}, recebe {1}")
        @CsvSource({
                "31.90, 30.00",   // o 0% de hoje no app: sobra 1.90, menos que 6% de 31.90
                "10.90, 10.50",   // app ficaria com -0.04
                "9.90, 9.68",     // app ficaria com -0.18
                "15.90, 14.95",   // um centavo acima dos 94%
                "30.90, 30.90"    // taxa zero
        })
        @DisplayName("recebe acima de 94% do preco: a taxa nao cobre os 6%")
        void proAboveNinetyFourPercent(String client, String pro) {
            assertThatThrownBy(() -> PriceSplit.of(brl(client), brl(pro)))
                    .isInstanceOf(InvalidPriceSplitException.class);
        }

        @ParameterizedTest(name = "cliente {0}")
        @CsvSource({"30.00", "31.99", "32.09", "32.91"})
        @DisplayName("preco do cliente fora da politica X,90")
        void clientPriceNotEndingInNinety(String client) {
            assertThatThrownBy(() -> PriceSplit.of(brl(client), brl("1.00")))
                    .isInstanceOf(InvalidPriceSplitException.class);
        }

        @Test
        @DisplayName("mais de 2 casas decimais nao e dinheiro")
        void moreThanTwoDecimals() {
            assertThatThrownBy(() -> PriceSplit.of(brl("32.90"), brl("30.001")))
                    .isInstanceOf(InvalidPriceSplitException.class);
            assertThatThrownBy(() -> PriceSplit.of(brl("32.901"), brl("30.00")))
                    .isInstanceOf(InvalidPriceSplitException.class);
        }

        @Test
        @DisplayName("valores ausentes, zero ou negativos")
        void missingZeroOrNegative() {
            assertThatThrownBy(() -> PriceSplit.of(null, brl("30.00"))).isInstanceOf(InvalidPriceSplitException.class);
            assertThatThrownBy(() -> PriceSplit.of(brl("32.90"), null)).isInstanceOf(InvalidPriceSplitException.class);
            assertThatThrownBy(() -> PriceSplit.of(brl("32.90"), brl("0"))).isInstanceOf(InvalidPriceSplitException.class);
            assertThatThrownBy(() -> PriceSplit.of(brl("32.90"), brl("-1.00"))).isInstanceOf(InvalidPriceSplitException.class);
            assertThatThrownBy(() -> PriceSplit.of(brl("-0.10"), brl("1.00"))).isInstanceOf(InvalidPriceSplitException.class);
        }
    }
}
