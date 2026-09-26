package com.ospaulos.vupth.unit.attendance;

import com.ospaulos.vupth.domain.attendance.penalty.ExitPenaltyRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests — regra da multa por sair da fila (D4: Andre 17/09, tolerancia 24/09, sem isencao 25/09).
 * Os casos "Como se verifica" do pedido do Client de 24/09 estao aqui literalmente.
 */
class ExitPenaltyRuleTest {

    private static final OffsetDateTime ENTERED = OffsetDateTime.parse("2026-09-25T14:00:00-03:00");

    @Test
    @DisplayName("tolerancia: espera de 30 min ou menos -> 5 min; acima de 30 -> 10 min")
    void tolerance_byWaitAtEntry() {
        assertThat(ExitPenaltyRule.toleranceMinutes(0)).isEqualTo(5);
        assertThat(ExitPenaltyRule.toleranceMinutes(30)).isEqualTo(5);
        assertThat(ExitPenaltyRule.toleranceMinutes(31)).isEqualTo(10);
        assertThat(ExitPenaltyRule.toleranceMinutes(59)).isEqualTo(10);
        assertThat(ExitPenaltyRule.toleranceMinutes(90)).isEqualTo(10);
    }

    @Test
    @DisplayName("valor pelo tempo que falta: <=30 -> R$ 5; 31..59 -> R$ 3; >=60 -> sem multa")
    void amount_byRemaining() {
        assertThat(ExitPenaltyRule.amountFor(0)).isEqualByComparingTo("5.00");
        assertThat(ExitPenaltyRule.amountFor(30)).isEqualByComparingTo("5.00");
        assertThat(ExitPenaltyRule.amountFor(31)).isEqualByComparingTo("3.00");
        assertThat(ExitPenaltyRule.amountFor(59)).isEqualByComparingTo("3.00");
        assertThat(ExitPenaltyRule.amountFor(60)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Client 24/09: 20 min de espera -> sair aos 4 min e de graca, aos 6 min custa")
    void shortWait_example() {
        OffsetDateTime until = ExitPenaltyRule.toleranceUntil(ENTERED, 20);
        assertThat(ExitPenaltyRule.exitPenalty(ENTERED.plusMinutes(4), until, 16)).isEqualByComparingTo("0.00");
        assertThat(ExitPenaltyRule.exitPenalty(ENTERED.plusMinutes(6), until, 14)).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("Client 24/09: 45 min de espera -> sair aos 9 min e de graca, aos 11 min custa")
    void mediumWait_example() {
        OffsetDateTime until = ExitPenaltyRule.toleranceUntil(ENTERED, 45);
        assertThat(ExitPenaltyRule.exitPenalty(ENTERED.plusMinutes(9), until, 36)).isEqualByComparingTo("0.00");
        assertThat(ExitPenaltyRule.exitPenalty(ENTERED.plusMinutes(11), until, 34)).isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("com 1h ou mais para a vez, sair nunca custa — nem depois da tolerancia")
    void longWait_neverCharged() {
        OffsetDateTime until = ExitPenaltyRule.toleranceUntil(ENTERED, 90);
        assertThat(ExitPenaltyRule.exitPenalty(ENTERED.plusMinutes(20), until, 70)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("no limite exato da tolerancia a saida ja custa (a janela e [entrada, limite))")
    void atToleranceLimit_charged() {
        OffsetDateTime until = ExitPenaltyRule.toleranceUntil(ENTERED, 20);
        assertThat(ExitPenaltyRule.exitPenalty(until, until, 15)).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("atendimento anterior a regra (sem tolerancia gravada): vale o valor pelo tempo")
    void noToleranceStamped_chargesByRemaining() {
        assertThat(ExitPenaltyRule.exitPenalty(ENTERED, null, 20)).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("o valor sai com centavos (0.00, 3.00, 5.00) — o app mostra sem formatar")
    void amounts_haveTwoDecimals() {
        assertThat(ExitPenaltyRule.amountFor(90).scale()).isEqualTo(2);
        assertThat(ExitPenaltyRule.amountFor(45)).isEqualTo(new BigDecimal("3.00"));
    }
}
