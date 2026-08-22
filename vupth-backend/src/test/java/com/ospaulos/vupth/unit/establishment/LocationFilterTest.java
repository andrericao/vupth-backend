package com.ospaulos.vupth.unit.establishment;

import com.ospaulos.vupth.domain.address.exception.InvalidUfException;
import com.ospaulos.vupth.domain.address.model.Address;
import com.ospaulos.vupth.domain.establishment.valueObject.LocationFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests — escopo de cidade/UF da listagem publica (backlog #29, fatia C do #2).
 *
 * <p>A matriz que importa esta aqui e nao no teste de integracao: casamento sem acento, UF por
 * igualdade e endereco ausente. O teste de integracao prova o contrato HTTP; este prova a regra.</p>
 */
class LocationFilterTest {

    @Test
    @DisplayName("Sem cidade e sem UF -> null: nao ha filtro nenhum, a lista sai inteira")
    void noFilterAtAll() {
        assertThat(LocationFilter.from(null, null)).isNull();
        assertThat(LocationFilter.from("  ", "")).isNull();
    }

    @Test
    @DisplayName("UF e normalizada para maiuscula e casa por IGUALDADE, nao por trecho")
    void ufIsExact() {
        LocationFilter rj = LocationFilter.from(null, "rj");

        assertThat(rj.uf()).isEqualTo("RJ");
        assertThat(rj.matches(address("Niteroi", "RJ"))).isTrue();
        assertThat(rj.matches(address("Sao Paulo", "SP"))).isFalse();
    }

    @Test
    @DisplayName("Cidade casa por trecho, sem caixa e SEM ACENTO — o modo de falha que importa")
    void cityIgnoresAccentAndCase() {
        // A cidade vem de geocodificacao reversa no aparelho; a do banco foi digitada pelo dono.
        // Sem a dobra, uma cedilha devolveria lista vazia e o usuario leria "nao ha lojas".
        LocationFilter filter = LocationFilter.from("sao goncalo", null);

        assertThat(filter.matches(address("São Gonçalo", "RJ"))).isTrue();
        assertThat(filter.matches(address("SAO GONCALO", "RJ"))).isTrue();
        assertThat(filter.matches(address("Niteroi", "RJ"))).isFalse();
    }

    @Test
    @DisplayName("Cidade acentuada na busca acha a cidade sem acento no banco (as duas pontas)")
    void foldingWorksBothWays() {
        assertThat(LocationFilter.from("São Gonçalo", null).matches(address("Sao Goncalo", "RJ"))).isTrue();
    }

    @Test
    @DisplayName("Cidade + UF: precisa das DUAS (ha 'Sao Jose' em quase todo estado)")
    void cityAndUfAreCombined() {
        LocationFilter filter = LocationFilter.from("Sao Jose", "SC");

        assertThat(filter.matches(address("São José", "SC"))).isTrue();
        assertThat(filter.matches(address("São José", "SP"))).isFalse();
        assertThat(filter.matches(address("Florianopolis", "SC"))).isFalse();
    }

    @Test
    @DisplayName("Endereco ausente NAO casa — a pergunta virou 'o que fica em X'")
    void withoutAddressThereIsNoAnswer() {
        assertThat(LocationFilter.from("Niteroi", "RJ").matches(null)).isFalse();
    }

    @Test
    @DisplayName("[ERRO] uf que nao e sigla de 2 letras -> 422 (nunca casaria com nada)")
    void invalidUfIsRejected() {
        for (String invalid : new String[] {"Rio de Janeiro", "SP1", "R", "12"}) {
            assertThatThrownBy(() -> LocationFilter.from(null, invalid))
                    .as("uf=%s", invalid)
                    .isInstanceOf(InvalidUfException.class)
                    .hasMessageContaining("2-letter");
        }
    }

    @Test
    @DisplayName("Espaco em volta e aparado nos dois campos")
    void valuesAreTrimmed() {
        LocationFilter filter = LocationFilter.from("  Niteroi  ", " rj ");

        assertThat(filter.uf()).isEqualTo("RJ");
        assertThat(filter.city()).isEqualTo("Niteroi");
        assertThat(filter.matches(address("Niteroi", "RJ"))).isTrue();
    }

    private Address address(String city, String state) {
        Address address = new Address();
        address.setCity(city);
        address.setState(state);
        return address;
    }
}
