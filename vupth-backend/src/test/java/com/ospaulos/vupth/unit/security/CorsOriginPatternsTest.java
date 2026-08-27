package com.ospaulos.vupth.unit.security;

import com.ospaulos.vupth.infrastructure.security.jwt.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests — a leitura da variavel {@code CORS_ALLOWED_ORIGINS}.
 *
 * <p>O caso que este teste guarda e o <b>default</b>: sem a variavel, a lista tem de sair
 * <b>vazia</b> (= nenhuma origem de navegador autorizada). Uma lista com um elemento em branco
 * nao seria "fechado" — seria um padrao invalido, e o modo de falha do CORS e sempre o mesmo
 * erro mudo no console, entao a diferenca nao apareceria em lugar nenhum ate alguem testar por link.
 */
class CorsOriginPatternsTest {

    @Test
    @DisplayName("variavel ausente/vazia = NENHUMA origem, nao um padrao em branco")
    void vazio_naoAutorizaNada() {
        assertThat(SecurityConfig.parseOriginPatterns("")).isEmpty();
        assertThat(SecurityConfig.parseOriginPatterns("   ")).isEmpty();
        assertThat(SecurityConfig.parseOriginPatterns(null)).isEmpty();
        assertThat(SecurityConfig.parseOriginPatterns(",,")).isEmpty();
    }

    @Test
    @DisplayName("lista com espacos ao redor das virgulas — o jeito que se digita no painel do Render")
    void espacos_saoAparados() {
        assertThat(SecurityConfig.parseOriginPatterns(
                " https://vupth-partner.pages.dev , https://*.vupth-partner.pages.dev "))
                .containsExactly("https://vupth-partner.pages.dev", "https://*.vupth-partner.pages.dev");
    }

    @Test
    @DisplayName("uma origem so continua valendo")
    void origemUnica() {
        assertThat(SecurityConfig.parseOriginPatterns("https://vupth-partner.pages.dev"))
                .containsExactly("https://vupth-partner.pages.dev");
    }
}
