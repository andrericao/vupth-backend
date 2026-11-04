package com.ospaulos.vupth.unit.user;

import com.ospaulos.vupth.domain.user.Cpf;
import com.ospaulos.vupth.domain.user.exception.CpfInvalidException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CpfTest {

    @Test
    @DisplayName("normaliza a mascara e confere os digitos")
    void normalize() {
        assertThat(Cpf.normalize("529.982.247-25")).isEqualTo("52998224725");
        assertThat(Cpf.normalize(" 52998224725 ")).isEqualTo("52998224725");
        assertThatThrownBy(() -> Cpf.normalize("111.111.111-11")).isInstanceOf(CpfInvalidException.class);
        assertThatThrownBy(() -> Cpf.normalize("529.982.247-26")).isInstanceOf(CpfInvalidException.class)
                .hasMessageNotContaining("52998224726");
        assertThatThrownBy(() -> Cpf.normalize("abc")).isInstanceOf(CpfInvalidException.class);
    }

    @Test
    @DisplayName("mascara deixando so os digitos do meio")
    void mask() {
        assertThat(Cpf.mask("52998224725")).isEqualTo("***.982.247-**");
        assertThat(Cpf.mask(null)).isNull();
        assertThat(Cpf.mask("123")).isNull();
    }
}
