package com.ospaulos.vupth.unit.testing;

import com.ospaulos.vupth.web.rest.testing.TestSeedController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static org.assertj.core.api.Assertions.assertThat;

class TestSeedGuardTest {

    @Test
    @DisplayName("semeador de teste: so liga com vupth.test-seed.enabled=true e sem valor padrao (producao nao define, a rota nao existe)")
    void onlyWhenExplicitlyEnabled() {
        ConditionalOnProperty cond = TestSeedController.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(cond).isNotNull();
        assertThat(cond.prefix()).isEqualTo("vupth.test-seed");
        assertThat(cond.name()).containsExactly("enabled");
        assertThat(cond.havingValue()).isEqualTo("true");
        assertThat(cond.matchIfMissing()).isFalse();
    }
}
