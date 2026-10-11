// Mesmo pacote da classe sob teste: usa o construtor package-private com RestClient e relogio.
package com.ospaulos.vupth.infrastructure.geocoding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests — os caminhos do geocoding (cobertura-98): CEP pela BrasilAPI, o fallback pelo Nominatim e
 * a leitura do reverso. O 429 e o cache estao em {@link HttpGeocoderRateLimitTest}.
 */
class HttpGeocoderTest {

    private static final String REVERSE = "http://nominatim.test/reverse";
    private static final String SEARCH = "http://nominatim.test/search";
    private static final String CEP = "http://brasilapi.test/cep";

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    private MockRestServiceServer server;
    private HttpGeocoder geocoder;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        geocoder = new HttpGeocoder(builder.build(), CEP, SEARCH, REVERSE, "vupth-test/1.0",
                Duration.ofSeconds(120), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static GeocodeQuery query(String zip, String street, String number) {
        return new GeocodeQuery(zip, street, number, "Penha", "Rio de Janeiro", "RJ");
    }

    // --- geocode ---

    @Test
    @DisplayName("Consulta nula ou sem CEP nem rua/cidade: vazio, sem request")
    void nothingToAsk() {
        assertThat(geocoder.geocode(null)).isEmpty();
        assertThat(geocoder.geocode(new GeocodeQuery("123", " ", null, null, null, null))).isEmpty();
        assertThat(geocoder.geocode(new GeocodeQuery(null, "Rua X", null, null, " ", null))).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("BrasilAPI com coordenada em texto resolve pelo CEP, sem ir ao Nominatim")
    void zipCodeWithStringCoordinates() {
        server.expect(ExpectedCount.once(), requestTo(CEP + "/21012000"))
                .andRespond(withSuccess("""
                        {"location":{"coordinates":{"latitude":"-22.8422","longitude":" -43.2764 "}}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(geocoder.geocode(query("21012000", "Rua X", "1")))
                .contains(new Coordinates(-22.8422, -43.2764));
        server.verify();
    }

    @Test
    @DisplayName("BrasilAPI com coordenada numerica e sem rua: resolve so pelo CEP")
    void zipCodeWithNumericCoordinates() {
        server.expect(ExpectedCount.once(), requestTo(CEP + "/21012000"))
                .andRespond(withSuccess("""
                        {"location":{"coordinates":{"latitude":-22.8,"longitude":-43.2}}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(geocoder.geocode(new GeocodeQuery("21012000", null, null, null, null, null)))
                .contains(new Coordinates(-22.8, -43.2));
        server.verify();
    }

    @Test
    @DisplayName("CEP sem bloco location e sem rua: vazio")
    void zipCodeWithoutLocation() {
        server.expect(ExpectedCount.once(), requestTo(CEP + "/21012000"))
                .andRespond(withSuccess("{\"cep\":\"21012000\"}", MediaType.APPLICATION_JSON));

        assertThat(geocoder.geocode(new GeocodeQuery("21012000", null, null, null, null, null))).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("CEP com location sem coordinates: vazio")
    void zipCodeWithoutCoordinates() {
        server.expect(ExpectedCount.once(), requestTo(CEP + "/21012000"))
                .andRespond(withSuccess("{\"location\":{\"type\":\"Point\"}}", MediaType.APPLICATION_JSON));

        assertThat(geocoder.geocode(new GeocodeQuery("21012000", null, null, null, null, null))).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("Coordenada da BrasilAPI ausente, ilegivel, de outro tipo ou implausivel e descartada")
    void zipCodeWithUnusableCoordinates() {
        server.expect(ExpectedCount.once(), requestTo(CEP + "/11111111"))
                .andRespond(withSuccess("{\"location\":{\"coordinates\":{\"latitude\":\"abc\",\"longitude\":\"-43\"}}}",
                        MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(CEP + "/22222222"))
                .andRespond(withSuccess("{\"location\":{\"coordinates\":{\"latitude\":true,\"longitude\":\"\"}}}",
                        MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(CEP + "/33333333"))
                .andRespond(withSuccess("{\"location\":{\"coordinates\":{\"latitude\":0,\"longitude\":0}}}",
                        MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(CEP + "/44444444"))
                .andRespond(withSuccess("{\"location\":{\"coordinates\":{\"latitude\":-22.8}}}",
                        MediaType.APPLICATION_JSON));

        for (String zip : new String[]{"11111111", "22222222", "33333333", "44444444"}) {
            assertThat(geocoder.geocode(new GeocodeQuery(zip, null, null, null, null, null))).isEmpty();
        }
        server.verify();
    }

    @Test
    @DisplayName("BrasilAPI fora do ar: cai no Nominatim, com numero na rua e o CEP na busca")
    void zipCodeFailsFallsBackToStreet() {
        server.expect(ExpectedCount.once(), requestTo(CEP + "/21012000"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(ExpectedCount.once(), requestTo(allOf(startsWith(SEARCH),
                        containsString("street=100%20Rua%20X"), containsString("postalcode=21012000"))))
                .andRespond(withSuccess("[{\"lat\":\"-22.84\",\"lon\":\"-43.27\"}]", MediaType.APPLICATION_JSON));

        assertThat(geocoder.geocode(query("21012000", "Rua X", "100")))
                .contains(new Coordinates(-22.84, -43.27));
        server.verify();
    }

    @Test
    @DisplayName("Sem CEP, sem numero e sem estado: a busca leva so a rua")
    void streetWithoutNumberNorState() {
        server.expect(ExpectedCount.once(), requestTo(allOf(startsWith(SEARCH),
                        containsString("street=Rua%20X&"), containsString("state=&"), containsString("postalcode="))))
                .andRespond(withSuccess("[{\"lat\":-22.84,\"lon\":-43.27}]", MediaType.APPLICATION_JSON));

        assertThat(geocoder.geocode(new GeocodeQuery(null, "Rua X", " ", null, "Rio de Janeiro", null)))
                .contains(new Coordinates(-22.84, -43.27));
        server.verify();
    }

    @Test
    @DisplayName("Nominatim sem resultado (lista vazia): vazio")
    void streetNotFound() {
        server.expect(ExpectedCount.once(), requestTo(startsWith(SEARCH)))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(geocoder.geocode(query(null, "Rua X", null))).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("Nominatim com item que nao e objeto: vazio")
    void streetResultNotAnObject() {
        server.expect(ExpectedCount.once(), requestTo(startsWith(SEARCH)))
                .andRespond(withSuccess("[\"x\"]", MediaType.APPLICATION_JSON));

        assertThat(geocoder.geocode(query(null, "Rua X", null))).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("Nominatim com coordenada implausivel: descartada")
    void streetImplausibleCoordinates() {
        server.expect(ExpectedCount.once(), requestTo(startsWith(SEARCH)))
                .andRespond(withSuccess("[{\"lat\":\"95\",\"lon\":\"-43\"}]", MediaType.APPLICATION_JSON));

        assertThat(geocoder.geocode(query(null, "Rua X", null))).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("Nominatim responde 404 (erro do cliente que nao pausa) ou JSON quebrado: vazio, sem pausa")
    void streetClientErrorAndBrokenBody() throws Exception {
        server.expect(ExpectedCount.once(), requestTo(startsWith(SEARCH)))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(geocoder.geocode(query(null, "Rua X", null))).isEmpty();
        assertThat(geocoder.unavailableUntil()).isEmpty();
        server.verify();

        server.reset();
        server.expect(ExpectedCount.once(), requestTo(startsWith(SEARCH)))
                .andRespond(withSuccess("{quebrado", MediaType.APPLICATION_JSON));
        // Segunda chamada ao Nominatim: o throttle segura ~1,1 s.
        assertThat(geocoder.geocode(query(null, "Rua X", null))).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("BrasilAPI e Nominatim com corpo vazio: vazio")
    void emptyBodies() {
        server.expect(ExpectedCount.once(), requestTo(CEP + "/21012000")).andRespond(withSuccess());
        server.expect(ExpectedCount.once(), requestTo(startsWith(SEARCH))).andRespond(withSuccess());

        assertThat(geocoder.geocode(query("21012000", "Rua X", null))).isEmpty();
        server.verify();
    }

    // --- reverse ---

    @Test
    @DisplayName("Reverso de coordenada nula ou implausivel: vazio, sem request")
    void reverseRejectsBadCoordinates() {
        assertThat(geocoder.reverse(null)).isEmpty();
        assertThat(geocoder.reverse(new Coordinates(0, 0))).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("Reverso le bairro, cidade e UF dos campos alternativos do OSM")
    void reverseUsesFallbackFields() {
        server.expect(ExpectedCount.once(), requestTo(startsWith(REVERSE)))
                .andRespond(withSuccess("""
                        {"address":{"road":" ","neighbourhood":"Vila","village":"Arraial",
                                    "state":"rj","postcode":"123"}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(geocoder.reverse(new Coordinates(-22.9, -43.1))).hasValueSatisfying(r -> {
            assertThat(r.street()).isNull();
            assertThat(r.number()).isNull();
            assertThat(r.neighborhood()).isEqualTo("Vila");
            assertThat(r.city()).isEqualTo("Arraial");
            assertThat(r.state()).isEqualTo("RJ");
            assertThat(r.zipCode()).isNull();
            assertThat(r.displayName()).isNull();
        });
        server.verify();
    }

    @Test
    @DisplayName("Reverso com estado por extenso e sem ISO: UF nula; sem nenhum bairro nem cidade: nulos")
    void reverseStateByNameIsNotGuessed() {
        server.expect(ExpectedCount.once(), requestTo(startsWith(REVERSE)))
                .andRespond(withSuccess("""
                        {"display_name":"Algum lugar",
                         "address":{"city_district":"Centro","municipality":"Niteroi",
                                    "ISO3166-2-lvl4":"XX","state":"Rio de Janeiro","postcode":"24020-000"}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(geocoder.reverse(new Coordinates(-22.9, -43.1))).hasValueSatisfying(r -> {
            assertThat(r.neighborhood()).isEqualTo("Centro");
            assertThat(r.city()).isEqualTo("Niteroi");
            assertThat(r.state()).isNull();
            assertThat(r.zipCode()).isEqualTo("24020000");
        });
        server.verify();
    }

    @Test
    @DisplayName("Reverso sem estado nenhum: UF nula")
    void reverseWithoutState() {
        server.expect(ExpectedCount.once(), requestTo(startsWith(REVERSE)))
                .andRespond(withSuccess("{\"address\":{\"town\":\"Mage\"}}", MediaType.APPLICATION_JSON));

        assertThat(geocoder.reverse(new Coordinates(-22.6, -43.0))).hasValueSatisfying(r -> {
            assertThat(r.city()).isEqualTo("Mage");
            assertThat(r.state()).isNull();
            assertThat(r.neighborhood()).isNull();
        });
        server.verify();
    }

    @Test
    @DisplayName("Reverso com corpo vazio: vazio (e guardado como 'nao achei')")
    void reverseEmptyBody() {
        server.expect(ExpectedCount.once(), requestTo(startsWith(REVERSE)))
                .andRespond(withSuccess());

        assertThat(geocoder.reverse(new Coordinates(-22.6, -43.0))).isEmpty();
        server.verify();
    }

    // --- pausa ---

    @Test
    @DisplayName("Retry-After em data HTTP, zero ou ausente: fica a pausa padrao")
    void retryAfterNotInSecondsUsesDefault() {
        HttpHeaders date = new HttpHeaders();
        date.add("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT");
        server.expect(ExpectedCount.once(), requestTo(startsWith(REVERSE)))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(date));

        assertThat(geocoder.reverse(new Coordinates(-22.6, -43.0))).isEmpty();
        assertThat(geocoder.unavailableUntil()).contains(NOW.plusSeconds(120));
        server.verify();
    }

    @Test
    @DisplayName("Retry-After zero: fica a pausa padrao")
    void retryAfterZeroUsesDefault() {
        HttpHeaders zero = new HttpHeaders();
        zero.add("Retry-After", " 0 ");
        server.expect(ExpectedCount.once(), requestTo(startsWith(SEARCH)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).headers(zero));

        assertThat(geocoder.geocode(query(null, "Rua X", null))).isEmpty();
        assertThat(geocoder.unavailableUntil()).contains(NOW.plusSeconds(120));
        server.verify();
    }

    @Test
    @DisplayName("429 sem headers na excecao: pausa padrao")
    void tooManyRequestsWithoutHeaders() throws Exception {
        java.lang.reflect.Method open = HttpGeocoder.class.getDeclaredMethod("openCooldownIfBlocked",
                HttpClientErrorException.class);
        open.setAccessible(true);
        open.invoke(geocoder, HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "429", null, null, null));

        assertThat(geocoder.unavailableUntil()).contains(NOW.plusSeconds(120));
    }

    // --- construtores ---

    @Test
    @DisplayName("Construtores publicos montam o geocoder com o RestClient de timeout")
    void publicConstructors() {
        HttpGeocoder legacy = new HttpGeocoder(CEP, SEARCH, REVERSE, "ua", 1);
        HttpGeocoder full = new HttpGeocoder(CEP, SEARCH, REVERSE, "ua", 1, 30);

        assertThat(legacy.unavailableUntil()).isEmpty();
        assertThat(full.unavailableUntil()).isEmpty();
        assertThat(HttpGeocoder.reverseCacheKey(new Coordinates(-22.84221, -43.27641))).isEqualTo("-22.8422,-43.2764");
    }
}
