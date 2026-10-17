package com.ospaulos.vupth.unit.logging;

import com.ospaulos.vupth.infrastructure.logging.LogMask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** S11 (DEC-81): o log leva a inicial e o dominio do e-mail, nunca o endereco inteiro. */
class LogMaskTest {

    @Test
    @DisplayName("e-mail vira inicial, asteriscos e dominio")
    void email() {
        assertThat(LogMask.email("ana.silva@gmail.com")).isEqualTo("a***@gmail.com");
    }

    @Test
    @DisplayName("nulo ou vazio vira traco; sem arroba ou arroba na frente vira ***")
    void edges() {
        assertThat(LogMask.email(null)).isEqualTo("-");
        assertThat(LogMask.email(" ")).isEqualTo("-");
        assertThat(LogMask.email("sem-arroba")).isEqualTo("***");
        assertThat(LogMask.email("@dominio.com")).isEqualTo("***");
    }
}
