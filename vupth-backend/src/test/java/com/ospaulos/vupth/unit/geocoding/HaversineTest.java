package com.ospaulos.vupth.unit.geocoding;

import com.ospaulos.vupth.domain.address.valueObject.Haversine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests — distancia entre coordenadas (backlog #2, fatia B).
 *
 * <p>Os pontos de referencia sao os mesmos do seed (`10-stubs/012-seed-data.yaml`): Penha,
 * Duque de Caxias, Belford Roxo e Niteroi. Assim a matriz de distancia testada aqui e a mesma que
 * o teste de integracao e o QA manual enxergam.</p>
 */
class HaversineTest {

    private static final double PENHA_LAT = -22.8422, PENHA_LNG = -43.2764;
    private static final double CAXIAS_LAT = -22.7858, CAXIAS_LNG = -43.3117;
    private static final double BELFORD_LAT = -22.7642, BELFORD_LNG = -43.3997;
    private static final double NITEROI_LAT = -22.8833, NITEROI_LNG = -43.1036;

    @Test
    @DisplayName("distancias do seed batem com a matriz documentada (tolerancia de 1 km)")
    void distanciasDoSeed() {
        assertThat(Haversine.distanceKm(PENHA_LAT, PENHA_LNG, CAXIAS_LAT, CAXIAS_LNG))
                .isCloseTo(7.2, org.assertj.core.data.Offset.offset(1.0));
        assertThat(Haversine.distanceKm(PENHA_LAT, PENHA_LNG, BELFORD_LAT, BELFORD_LNG))
                .isCloseTo(15.3, org.assertj.core.data.Offset.offset(1.0));
        assertThat(Haversine.distanceKm(PENHA_LAT, PENHA_LNG, NITEROI_LAT, NITEROI_LNG))
                .isCloseTo(18.3, org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    @DisplayName("ponto igual a si mesmo da EXATAMENTE zero, sem NaN")
    void mesmoPonto() {
        // O asin(sqrt(a)) com a levemente > 1 por arredondamento devolveria NaN — que passaria
        // silenciosamente por qualquer comparacao e envenenaria a ordenacao.
        double d = Haversine.distanceKm(PENHA_LAT, PENHA_LNG, PENHA_LAT, PENHA_LNG);
        assertThat(d).isNotNaN().isEqualTo(0.0);
    }

    @Test
    @DisplayName("a distancia e simetrica — ida e volta dao o mesmo numero")
    void simetrica() {
        assertThat(Haversine.distanceKm(PENHA_LAT, PENHA_LNG, NITEROI_LAT, NITEROI_LNG))
                .isEqualTo(Haversine.distanceKm(NITEROI_LAT, NITEROI_LNG, PENHA_LAT, PENHA_LNG));
    }

    @Test
    @DisplayName("atravessa o equador e o meridiano sem trocar de sinal indevidamente")
    void cruzaOrigemDosSinais() {
        // Um ponto ao norte e outro ao sul do equador: se algum sinal se perdesse, a distancia
        // encolheria para ~0 em vez de somar os dois lados.
        double d = Haversine.distanceKm(1.0, 0.5, -1.0, -0.5);
        assertThat(d).isCloseTo(248.0, org.assertj.core.data.Offset.offset(5.0));
    }

    @Test
    @DisplayName("antipodas dao ~meia circunferencia, nao estouram nem viram NaN")
    void antipodas() {
        double d = Haversine.distanceKm(0.0, 0.0, 0.0, 180.0);
        assertThat(d).isNotNaN().isCloseTo(20015.0, org.assertj.core.data.Offset.offset(10.0));
    }

    @Test
    @DisplayName("roundKm corta o ruido em 2 casas — e o numero que o card mostra")
    void arredondamento() {
        assertThat(Haversine.roundKm(7.234567)).isEqualTo(7.23);
        assertThat(Haversine.roundKm(7.235)).isEqualTo(7.24);
        assertThat(Haversine.roundKm(0.0)).isEqualTo(0.0);
    }
}
