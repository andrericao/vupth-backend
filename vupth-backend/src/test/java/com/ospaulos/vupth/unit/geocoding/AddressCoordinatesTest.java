package com.ospaulos.vupth.unit.geocoding;

import com.ospaulos.vupth.domain.address.exception.InvalidCoordinatesException;
import com.ospaulos.vupth.domain.address.valueObject.AddressCoordinates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests — portao de entrada da coordenada que chega pela API (backlog #2, fatia A2).
 *
 * <p>A fatia A ja checava o que <b>saia</b> do geocoder ({@code Coordinates.isPlausible}); esta e a
 * outra ponta, o que <b>entra</b> pelo request. Vale mais do que parece: coordenada errada e pior que
 * coordenada ausente — sem coordenada a loja so nao aparece na busca por raio, com coordenada errada
 * ela aparece no lugar errado e ninguem percebe.</p>
 */
class AddressCoordinatesTest {

    @Test
    @DisplayName("Par valido passa")
    void validPairIsAccepted() {
        assertThatCode(() -> AddressCoordinates.validate(-22.8422, -43.2764)).doesNotThrowAnyException();
        assertThatCode(() -> AddressCoordinates.validate(-90.0, -180.0)).doesNotThrowAnyException();
        assertThatCode(() -> AddressCoordinates.validate(90.0, 180.0)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Ambos nulos e VALIDO — e o cadastro por texto, que o geocoding resolve depois")
    void bothNullIsTheNormalCase() {
        assertThatCode(() -> AddressCoordinates.validate(null, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("So latitude ou so longitude -> 422 (meia coordenada nao localiza nada)")
    void halfCoordinateIsRejected() {
        assertThatThrownBy(() -> AddressCoordinates.validate(-22.8422, null))
                .isInstanceOf(InvalidCoordinatesException.class)
                .hasMessageContaining("together");

        assertThatThrownBy(() -> AddressCoordinates.validate(null, -43.2764))
                .isInstanceOf(InvalidCoordinatesException.class)
                .hasMessageContaining("together");
    }

    @Test
    @DisplayName("Fora do globo -> 422")
    void outOfGlobeIsRejected() {
        assertThatThrownBy(() -> AddressCoordinates.validate(-91.0, 0.0))
                .isInstanceOf(InvalidCoordinatesException.class);
        assertThatThrownBy(() -> AddressCoordinates.validate(0.0, 181.0))
                .isInstanceOf(InvalidCoordinatesException.class);
    }

    @Test
    @DisplayName("(0,0) -> 422 — a 'ilha nula' e o zero-default de um front sem fix de GPS")
    void nullIslandIsRejected() {
        // No front esse par nao vem de um erro de digitacao: vem de um objeto de localizacao nao
        // preenchido. Aceitar em silencio poria a loja no Golfo da Guine.
        assertThatThrownBy(() -> AddressCoordinates.validate(0.0, 0.0))
                .isInstanceOf(InvalidCoordinatesException.class)
                .hasMessageContaining("(0,0)");
    }
}
