package com.ospaulos.vupth.domain.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchTextTest {

    @Test
    @DisplayName("fold tira acento, baixa a caixa e apara as pontas")
    void fold_stripsAccentsAndCase() {
        assertThat(SearchText.fold("  João Conceição ")).isEqualTo("joao conceicao");
        assertThat(SearchText.fold("ÁÉÍÓÚ Ç Ñ ü")).isEqualTo("aeiou c n u");
    }

    @Test
    @DisplayName("fold de null continua null")
    void fold_null() {
        assertThat(SearchText.fold(null)).isNull();
    }

    @Test
    @DisplayName("ACCENTED e PLAIN casam letra a letra, e o PLAIN e o fold do ACCENTED")
    void translateSets_matchFold() {
        assertThat(SearchText.ACCENTED).hasSameSizeAs(SearchText.PLAIN);
        assertThat(SearchText.fold(SearchText.ACCENTED)).isEqualTo(SearchText.PLAIN);
    }
}
