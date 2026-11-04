package com.ospaulos.vupth.infrastructure.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** DEC-180: a mesma lista do CORS vale para a Origin das escritas por cookie e para o /ws. */
@DisplayName("origens autorizadas")
class AllowedOriginsTest {

    @Test
    @DisplayName("casa a exata e o curinga do meio; recusa o resto, a vazia e a nula")
    void matches() {
        AllowedOrigins o = new AllowedOrigins("https://app.vupth.com, https://*.vupth-partner.pages.dev");
        assertThat(o.patterns()).containsExactly("https://app.vupth.com", "https://*.vupth-partner.pages.dev");
        assertThat(o.allows("https://app.vupth.com")).isTrue();
        assertThat(o.allows("https://abc.vupth-partner.pages.dev")).isTrue();
        assertThat(o.allows("https://vupth.com.evil.com")).isFalse();
        assertThat(o.allows("http://app.vupth.com")).isFalse();
        assertThat(o.allows("")).isFalse();
        assertThat(o.allows(null)).isFalse();
    }

    @Test
    @DisplayName("lista vazia: nenhuma origem")
    void empty() {
        AllowedOrigins o = new AllowedOrigins("");
        assertThat(o.patterns()).isEmpty();
        assertThat(o.allows("https://app.vupth.com")).isFalse();
    }
}
