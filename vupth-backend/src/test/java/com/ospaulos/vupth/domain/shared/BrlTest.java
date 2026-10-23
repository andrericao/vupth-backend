package com.ospaulos.vupth.domain.shared;

import com.ospaulos.vupth.domain.attendance.exception.ExitPenaltyNotAcknowledgedException;
import com.ospaulos.vupth.domain.serviceItem.exception.InvalidPriceSplitException;
import com.ospaulos.vupth.domain.serviceItem.valueObject.PriceSplit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("valor-em-reais-nas-mensagens: R$ 53,90 e R$ 1.234,56, nunca R$ 53.90")
class BrlTest {

    @Test
    @DisplayName("virgula nos centavos e ponto no milhar; nulo vira R$ 0,00")
    void formats() {
        assertThat(Brl.of(new BigDecimal("53.9"))).isEqualTo("R$ 53,90");
        assertThat(Brl.of(new BigDecimal("1234.56"))).isEqualTo("R$ 1.234,56");
        assertThat(Brl.of(new BigDecimal("0.5"))).isEqualTo("R$ 0,50");
        assertThat(Brl.of(null)).isEqualTo("R$ 0,00");
    }

    @Test
    @DisplayName("aviso de saida da fila escreve a multa em reais")
    void exitPenaltyMessage() {
        assertThat(new ExitPenaltyNotAcknowledgedException(new BigDecimal("5.00"), null).getMessage())
                .contains("R$ 5,00").doesNotContain("5.00");
        assertThat(new ExitPenaltyNotAcknowledgedException(new BigDecimal("1000.00"), new BigDecimal("5.00")).getMessage())
                .contains("R$ 1.000,00").contains("R$ 5,00");
    }

    @Test
    @DisplayName("erro de preco do servico escreve os valores em reais")
    void priceSplitMessages() {
        assertThatThrownBy(() -> PriceSplit.of(new BigDecimal("53.50"), new BigDecimal("50.00"), new BigDecimal("50.00")))
                .isInstanceOf(InvalidPriceSplitException.class).hasMessageContaining("R$ 53,50");
        assertThatThrownBy(() -> PriceSplit.of(new BigDecimal("53.90"), new BigDecimal("60.00"), new BigDecimal("50.00")))
                .isInstanceOf(InvalidPriceSplitException.class).hasMessageContaining("R$ 50,00").hasMessageContaining("R$ 60,00");
    }
}
