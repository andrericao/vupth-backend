package com.ospaulos.vupth.unit.establishment;

import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentPageResponse;
import com.ospaulos.vupth.domain.establishment.exception.InvalidShowcaseFilterException;
import com.ospaulos.vupth.domain.establishment.valueObject.ShowcasePage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Vitrine paginada (BAK-2): opt-in, defaults e o corte da lista ja ordenada. */
@DisplayName("ShowcasePage — paginacao da vitrine")
class ShowcasePageTest {

    @Test
    @DisplayName("sem page nem size: null — a lista inteira de sempre")
    void optIn() {
        assertThat(ShowcasePage.from(null, null)).isNull();
    }

    @Test
    @DisplayName("so um dos dois: o outro assume o default (pagina 0, 20 itens)")
    void defaults() {
        assertThat(ShowcasePage.from(2, null)).isEqualTo(new ShowcasePage(2, 20));
        assertThat(ShowcasePage.from(null, 5)).isEqualTo(new ShowcasePage(0, 5));
        assertThat(ShowcasePage.from(0, 100)).isEqualTo(new ShowcasePage(0, 100));
    }

    @Test
    @DisplayName("page negativa e size fora de 1..100 sao recusados")
    void invalid() {
        assertThatThrownBy(() -> ShowcasePage.from(-1, null)).isInstanceOf(InvalidShowcaseFilterException.class);
        assertThatThrownBy(() -> ShowcasePage.from(null, 0)).isInstanceOf(InvalidShowcaseFilterException.class);
        assertThatThrownBy(() -> ShowcasePage.from(null, 101)).isInstanceOf(InvalidShowcaseFilterException.class);
    }

    @Test
    @DisplayName("slice e totalPages: meio, fim parcial e alem do fim")
    void slice() {
        List<Integer> all = List.of(1, 2, 3, 4, 5);
        assertThat(new ShowcasePage(0, 2).slice(all)).containsExactly(1, 2);
        assertThat(new ShowcasePage(2, 2).slice(all)).containsExactly(5);
        assertThat(new ShowcasePage(3, 2).slice(all)).isEmpty();
        assertThat(new ShowcasePage(0, 2).totalPages(5)).isEqualTo(3);
        assertThat(new ShowcasePage(0, 2).totalPages(0)).isZero();
    }

    @Test
    @DisplayName("envelope: hasNext so quando ha pagina seguinte; lista vazia da 0 paginas")
    void envelope() {
        EstablishmentPageResponse empty = EstablishmentPageResponse.of(List.of(), new ShowcasePage(0, 20));
        assertThat(empty.items()).isEmpty();
        assertThat(empty.totalItems()).isZero();
        assertThat(empty.totalPages()).isZero();
        assertThat(empty.hasNext()).isFalse();
    }
}
