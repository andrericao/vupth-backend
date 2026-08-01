package com.ospaulos.vupth.unit.geocoding;

import com.ospaulos.vupth.infrastructure.geocoding.Coordinates;
import com.ospaulos.vupth.infrastructure.geocoding.GeocodeQuery;
import com.ospaulos.vupth.infrastructure.geocoding.NoOpGeocoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests — contratos dos value objects do geocoding (backlog #2, fatia A). Sao as guardas que
 * evitam gravar lixo como coordenada e evitam gastar request externo com endereco que nao da para
 * consultar.
 */
class GeocodingContractTest {

    @Test
    @DisplayName("Coordenada dentro do globo e plausivel")
    void plausibleCoordinates() {
        assertThat(new Coordinates(-22.8422, -43.2764).isPlausible()).isTrue();  // Penha, RJ
        assertThat(new Coordinates(-90, -180).isPlausible()).isTrue();           // extremos validos
        assertThat(new Coordinates(90, 180).isPlausible()).isTrue();
    }

    @Test
    @DisplayName("Coordenada fora do globo e descartada")
    void outOfGlobeIsRejected() {
        assertThat(new Coordinates(-91, 0).isPlausible()).isFalse();
        assertThat(new Coordinates(0, 181).isPlausible()).isFalse();
    }

    @Test
    @DisplayName("(0,0) e descartada — 'ilha nula' do geocoder que nao achou nada")
    void nullIslandIsRejected() {
        // Um servico que devolve zeros no lugar de "nao encontrei" gravaria uma loja no Golfo da
        // Guine, que apareceria como a mais distante do mundo em qualquer busca por raio.
        assertThat(new Coordinates(0, 0).isPlausible()).isFalse();
    }

    @Test
    @DisplayName("CEP so e consultavel com 8 digitos puros (convencao de Address.zipCode)")
    void zipCodeMustBeEightDigits() {
        assertThat(query("21012000", "Rua X", "Rio de Janeiro").hasUsableZipCode()).isTrue();
        assertThat(query("21012-000", "Rua X", "Rio de Janeiro").hasUsableZipCode()).isFalse(); // com hifen
        assertThat(query("2101200", "Rua X", "Rio de Janeiro").hasUsableZipCode()).isFalse();   // 7 digitos
        assertThat(query(null, "Rua X", "Rio de Janeiro").hasUsableZipCode()).isFalse();
        assertThat(query("abcdefgh", "Rua X", "Rio de Janeiro").hasUsableZipCode()).isFalse();
    }

    @Test
    @DisplayName("Busca livre exige rua E cidade — sem isso nao ha o que procurar")
    void streetAddressNeedsStreetAndCity() {
        assertThat(query("21012000", "Avenida Bras de Pina", "Rio de Janeiro").hasUsableStreetAddress()).isTrue();
        assertThat(query("21012000", null, "Rio de Janeiro").hasUsableStreetAddress()).isFalse();
        assertThat(query("21012000", "Avenida Bras de Pina", null).hasUsableStreetAddress()).isFalse();
        assertThat(query("21012000", "   ", "Rio de Janeiro").hasUsableStreetAddress()).isFalse();
    }

    @Test
    @DisplayName("NoOpGeocoder (provider=none) nunca resolve e nunca lanca — nem com query nula")
    void noOpNeverResolves() {
        NoOpGeocoder geocoder = new NoOpGeocoder();
        assertThat(geocoder.geocode(query("21012000", "Rua X", "Rio de Janeiro"))).isEmpty();
        assertThat(geocoder.geocode(null)).isEmpty();
    }

    private GeocodeQuery query(String zip, String street, String city) {
        return new GeocodeQuery(zip, street, "100", "Penha", city, "RJ");
    }
}
