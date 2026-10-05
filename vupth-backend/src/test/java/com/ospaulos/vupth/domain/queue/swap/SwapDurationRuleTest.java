package com.ospaulos.vupth.domain.queue.swap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SwapDurationRuleTest {

    @Test
    @DisplayName("D14: ate 10 min de diferenca troca, nos dois sentidos")
    void upToTenMinutes_compatible() {
        assertThat(SwapDurationRule.compatible(30, 30)).isTrue();
        assertThat(SwapDurationRule.compatible(30, 40)).isTrue();
        assertThat(SwapDurationRule.compatible(40, 30)).isTrue();
    }

    @Test
    @DisplayName("D14: 11 min ou mais nao troca (30 min x 2h)")
    void moreThanTenMinutes_incompatible() {
        assertThat(SwapDurationRule.compatible(30, 41)).isFalse();
        assertThat(SwapDurationRule.compatible(30, 120)).isFalse();
    }
}
