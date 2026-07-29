package com.ospaulos.vupth.infrastructure.geocoding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Geocoder de producao (backlog #2), <b>gratuito e sem chave</b> — a restricao de custo zero (Andre
 * 2026-07-27) e o que define este desenho:
 *
 * <ol>
 *   <li><b>BrasilAPI CEP v2</b> ({@code /api/cep/v2/{cep}}) — via primaria. Gratuita, sem cadastro,
 *       sem chave. Devolve {@code location.coordinates} quando conhece o CEP.</li>
 *   <li><b>Nominatim / OpenStreetMap</b> — fallback por endereco textual quando o CEP nao trouxe
 *       coordenada. Gratuito, mas com <b>policy de uso obrigatoria</b>: {@code User-Agent} proprio
 *       identificando a aplicacao e <b>no maximo 1 request por segundo</b> — respeitado aqui pelo
 *       intervalo minimo em {@link #throttleNominatim()}.</li>
 * </ol>
 *
 * <p><b>Nunca lanca</b> (contrato do {@link Geocoder}): qualquer falha vira {@code Optional.empty()}
 * + log. E chamado de uma thread de fundo, entao a latencia nao aparece no cadastro; ainda assim os
 * timeouts existem para nao prender a fila de geocoding num servico pendurado.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vupth.geocoding", name = "provider", havingValue = "http", matchIfMissing = true)
public class HttpGeocoder implements Geocoder {

    /** Policy do Nominatim: no maximo 1 req/s. Guardamos uma folga sobre o minimo exigido. */
    private static final long NOMINATIM_MIN_INTERVAL_MS = 1_100;

    private final RestClient restClient;
    private final String brasilApiUrl;
    private final String nominatimUrl;
    private final String userAgent;

    private long lastNominatimCallAt = 0L;

    public HttpGeocoder(
            @Value("${vupth.geocoding.brasilapi.url:https://brasilapi.com.br/api/cep/v2}") String brasilApiUrl,
            @Value("${vupth.geocoding.nominatim.url:https://nominatim.openstreetmap.org/search}") String nominatimUrl,
            @Value("${vupth.geocoding.user-agent:vupth-backend/1.0 (contato: no-reply@vupth.app)}") String userAgent,
            @Value("${vupth.geocoding.timeout-seconds:5}") int timeoutSeconds) {

        this.brasilApiUrl = brasilApiUrl;
        this.nominatimUrl = nominatimUrl;
        this.userAgent = userAgent;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Optional<Coordinates> geocode(GeocodeQuery query) {
        if (query == null) {
            return Optional.empty();
        }

        if (query.hasUsableZipCode()) {
            Optional<Coordinates> byCep = byZipCode(query.zipCode());
            if (byCep.isPresent()) {
                return byCep;
            }
        }

        if (query.hasUsableStreetAddress()) {
            return byStreetAddress(query);
        }

        log.info("[Geocoding] endereco sem CEP valido nem rua/cidade — nada a consultar");
        return Optional.empty();
    }

    /** Via primaria: BrasilAPI CEP v2. Nem todo CEP tem coordenada — nesse caso cai no fallback. */
    private Optional<Coordinates> byZipCode(String zipCode) {
        try {
            Map<?, ?> body = restClient.get()
                    .uri(brasilApiUrl + "/" + zipCode)
                    .header("User-Agent", userAgent)
                    .retrieve()
                    .body(Map.class);

            if (body == null || !(body.get("location") instanceof Map<?, ?> location)) {
                log.info("[Geocoding] BrasilAPI: CEP {} sem bloco location", zipCode);
                return Optional.empty();
            }
            if (!(location.get("coordinates") instanceof Map<?, ?> coordinates)) {
                log.info("[Geocoding] BrasilAPI: CEP {} sem coordenadas", zipCode);
                return Optional.empty();
            }
            // A BrasilAPI devolve os valores como STRING ("-43.2764"); toDouble tolera numero tambem.
            Optional<Coordinates> resolved = build(
                    toDouble(coordinates.get("latitude")), toDouble(coordinates.get("longitude")));
            resolved.ifPresent(c -> log.info("[Geocoding] BrasilAPI resolveu o CEP {} -> {},{}",
                    zipCode, c.latitude(), c.longitude()));
            return resolved;

        } catch (Exception e) {
            log.warn("[Geocoding] BrasilAPI falhou para o CEP {}: {}", zipCode, e.toString());
            return Optional.empty();
        }
    }

    /** Fallback: busca livre no Nominatim/OSM, restrita ao Brasil e ao primeiro resultado. */
    private Optional<Coordinates> byStreetAddress(GeocodeQuery query) {
        try {
            throttleNominatim();

            String street = query.number() == null || query.number().isBlank()
                    ? query.street()
                    : query.number() + " " + query.street();

            String uri = UriComponentsBuilder.fromUriString(nominatimUrl)
                    .queryParam("format", "jsonv2")
                    .queryParam("limit", 1)
                    .queryParam("countrycodes", "br")
                    .queryParam("street", street)
                    .queryParam("city", query.city())
                    .queryParam("state", query.state() == null ? "" : query.state())
                    .queryParam("postalcode", query.hasUsableZipCode() ? query.zipCode() : "")
                    .encode()
                    .toUriString();

            List<?> results = restClient.get()
                    .uri(uri)
                    // Exigido pela policy do Nominatim: requisicao anonima pode ser bloqueada.
                    .header("User-Agent", userAgent)
                    .retrieve()
                    .body(List.class);

            if (results == null || results.isEmpty() || !(results.get(0) instanceof Map<?, ?> first)) {
                log.info("[Geocoding] Nominatim nao encontrou '{}, {}'", street, query.city());
                return Optional.empty();
            }
            Optional<Coordinates> resolved = build(toDouble(first.get("lat")), toDouble(first.get("lon")));
            resolved.ifPresent(c -> log.info("[Geocoding] Nominatim resolveu '{}, {}' -> {},{}",
                    street, query.city(), c.latitude(), c.longitude()));
            return resolved;

        } catch (Exception e) {
            log.warn("[Geocoding] Nominatim falhou para '{}': {}", query.street(), e.toString());
            return Optional.empty();
        }
    }

    /**
     * Segura a chamada para nao passar de 1 req/s no Nominatim. O geocoding roda numa unica thread de
     * fundo, entao dormir aqui nao atrasa request nenhum de usuario — so espaca a fila.
     */
    private synchronized void throttleNominatim() throws InterruptedException {
        long since = System.currentTimeMillis() - lastNominatimCallAt;
        if (since < NOMINATIM_MIN_INTERVAL_MS) {
            Thread.sleep(NOMINATIM_MIN_INTERVAL_MS - since);
        }
        lastNominatimCallAt = System.currentTimeMillis();
    }

    private Optional<Coordinates> build(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            return Optional.empty();
        }
        Coordinates coordinates = new Coordinates(latitude, longitude);
        if (!coordinates.isPlausible()) {
            log.warn("[Geocoding] coordenada implausivel descartada: {},{}", latitude, longitude);
            return Optional.empty();
        }
        return Optional.of(coordinates);
    }

    /** Aceita numero ou string — a BrasilAPI manda string, o Nominatim tambem. */
    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
