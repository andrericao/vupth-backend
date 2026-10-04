package com.ospaulos.vupth.domain.establishment.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RatingTotalsTest {

    @Test
    @DisplayName("sem voto: media e melhor nulos, contagem zero")
    void empty() {
        var totals = EstablishmentServiceImpl.RatingTotals.EMPTY;
        assertThat(totals.average()).isNull();
        assertThat(totals.count()).isZero();
        assertThat(totals.best()).isNull();
    }

    @Test
    @DisplayName("media da loja e PONDERADA pelos votos, nao media das medias; melhor = maior media")
    void weightedAverageAndBest() {
        // A: 1 voto nota 5 (media 5,0) · B: 9 votos somando 27 (media 3,0)
        var totals = EstablishmentServiceImpl.RatingTotals.EMPTY.plus(5, 1).plus(27, 9);
        assertThat(totals.count()).isEqualTo(10);
        assertThat(totals.average()).isEqualTo(3.2); // 32/10 — a media das medias daria 4,0
        assertThat(totals.best()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("arredonda em 2 casas, como a media do profissional")
    void roundsToTwoDecimals() {
        var totals = EstablishmentServiceImpl.RatingTotals.EMPTY.plus(14, 3);
        assertThat(totals.average()).isEqualTo(4.67);
        assertThat(totals.best()).isEqualTo(4.67);
    }
}
