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
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

/**
 * Unit tests — o 429 do Nominatim (geocoder-429, log do Render de 05/10 12:08: {@code reverso falhou
 * para -22.8422,-43.2764: 429 Too many requests}). O que se guarda aqui: o mesmo ponto nao gasta
 * outro request (cache do reverso), e depois de um 429 nenhum pedido sai ate a pausa acabar — insistir
 * so prolonga o bloqueio do IP.
 */
class HttpGeocoderRateLimitTest {

    private static final String REVERSE = "http://nominatim.test/reverse";
    private static final String SEARCH = "http://nominatim.test/search";
    private static final String CEP = "http://brasilapi.test/cep";

    private static final String REVERSE_BODY = """
            {"display_name":"Avenida Bras de Pina, 100, Penha, Rio de Janeiro",
             "address":{"road":"Avenida Bras de Pina","house_number":"100","suburb":"Penha",
                        "city":"Rio de Janeiro","ISO3166-2-lvl4":"BR-RJ","postcode":"21012-000"}}
            """;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-05T15:08:00Z"));

    private MockRestServiceServer nominatim;
    private HttpGeocoder geocoder;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        nominatim = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        geocoder = new HttpGeocoder(builder.build(), CEP, SEARCH, REVERSE, "vupth-test/1.0 (contato: teste@vupth.app)",
                Duration.ofSeconds(120), clock);
    }

    @Test
    @DisplayName("Reverso do mesmo ponto (a ~11 m) sai do cache — um request so ao Nominatim")
    void reverseIsCachedByRoundedCoordinate() {
        nominatim.expect(ExpectedCount.once(), requestTo(startsWith(REVERSE)))
                .andExpect(header("User-Agent", "vupth-test/1.0 (contato: teste@vupth.app)"))
                .andRespond(withSuccess(REVERSE_BODY, MediaType.APPLICATION_JSON));

        assertThat(geocoder.reverse(new Coordinates(-22.84221, -43.27641))).isPresent();
        // Mesmo balcao, GPS oscilando na 5a casa: nao pode virar outro request.
        assertThat(geocoder.reverse(new Coordinates(-22.84224, -43.27638)))
                .hasValueSatisfying(r -> assertThat(r.street()).isEqualTo("Avenida Bras de Pina"));

        nominatim.verify();
    }

    @Test
    @DisplayName("Cache do reverso vence em 24 h")
    void reverseCacheExpires() {
        nominatim.expect(ExpectedCount.twice(), requestTo(startsWith(REVERSE)))
                .andRespond(withSuccess(REVERSE_BODY, MediaType.APPLICATION_JSON));

        assertThat(geocoder.reverse(new Coordinates(-22.8422, -43.2764))).isPresent();
        now.set(now.get().plus(Duration.ofHours(25)));
        assertThat(geocoder.reverse(new Coordinates(-22.8422, -43.2764))).isPresent();

        nominatim.verify();
    }

    @Test
    @DisplayName("429 no reverso abre a pausa: o pedido seguinte nem sai, e devolve vazio na hora")
    void tooManyRequestsOpensCooldown() {
        nominatim.expect(ExpectedCount.once(), requestTo(startsWith(REVERSE)))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(geocoder.reverse(new Coordinates(-22.8422, -43.2764))).isEmpty();
        assertThat(geocoder.unavailableUntil()).contains(now.get().plusSeconds(120));

        // Outro ponto, e a busca do cadastro: nenhum dos dois pode chegar ao Nominatim durante a pausa.
        assertThat(geocoder.reverse(new Coordinates(-23.5505, -46.6333))).isEmpty();
        assertThat(geocoder.geocode(new GeocodeQuery(null, "Rua X", "100", "Penha", "Rio de Janeiro", "RJ")))
                .isEmpty();

        nominatim.verify(); // so o primeiro pedido saiu
    }

    @Test
    @DisplayName("Retry-After do servidor define a pausa; passada a pausa, o Nominatim volta a ser chamado")
    void retryAfterIsHonoredAndCooldownEnds() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "30");
        nominatim.expect(ExpectedCount.once(), requestTo(startsWith(REVERSE)))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers));

        assertThat(geocoder.reverse(new Coordinates(-22.8422, -43.2764))).isEmpty();
        assertThat(geocoder.unavailableUntil()).contains(now.get().plusSeconds(30));

        now.set(now.get().plusSeconds(31));
        assertThat(geocoder.unavailableUntil()).isEmpty();

        nominatim.reset();
        nominatim.expect(ExpectedCount.once(), requestTo(startsWith(REVERSE)))
                .andRespond(withSuccess(REVERSE_BODY, MediaType.APPLICATION_JSON));
        assertThat(geocoder.reverse(new Coordinates(-22.8422, -43.2764))).isPresent();
        nominatim.verify();
    }

    @Test
    @DisplayName("403 (bloqueio da policy) tambem pausa; 500 nao — e falha passageira, nao recusa")
    void forbiddenPausesServerErrorDoesNot() {
        nominatim.expect(ExpectedCount.once(), requestTo(startsWith(REVERSE)))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(geocoder.reverse(new Coordinates(-22.8422, -43.2764))).isEmpty();
        assertThat(geocoder.unavailableUntil()).isEmpty();
        nominatim.verify();

        nominatim.reset();
        nominatim.expect(ExpectedCount.once(), requestTo(startsWith(REVERSE)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThat(geocoder.reverse(new Coordinates(-22.8422, -43.2764))).isEmpty();
        assertThat(geocoder.unavailableUntil()).isPresent();
        nominatim.verify();
    }

    @Test
    @DisplayName("Retry-After absurdo e limitado a 1 h — um valor errado nao desliga o geocoding por dias")
    void retryAfterIsCapped() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "999999");
        nominatim.expect(ExpectedCount.once(), requestTo(startsWith(SEARCH)))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers));

        assertThat(geocoder.geocode(new GeocodeQuery(null, "Rua X", "100", "Penha", "Rio de Janeiro", "RJ")))
                .isEmpty();
        assertThat(geocoder.unavailableUntil()).contains(now.get().plus(Duration.ofHours(1)));
        nominatim.verify();
    }
}
