package com.ospaulos.vupth.infrastructure.geocoding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

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
 * <p><b>429 do Nominatim (geocoder-429).</b> O 1 req/s e por processo, mas o Nominatim conta por IP — e
 * o IP de saida do Render/Railway e dividido com outros clientes. Por isso, alem do intervalo: (a) o
 * reverso guarda o resultado por coordenada arredondada ({@link #REVERSE_CACHE_DECIMALS} casas, ~11 m),
 * entao o dono que repete a tela nao gasta outro request; (b) um 429 (ou 403, o bloqueio da policy)
 * abre uma <b>pausa</b> — o {@code Retry-After} do servidor ou {@code cooldown-seconds} — em que
 * nenhum pedido sai, e quem chamou recebe vazio na hora. Sem retentativa em laco: quem quiser tentar
 * de novo consulta {@link #unavailableUntil()}.</p>
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

    /** 4 casas decimais ~ 11 m: o mesmo balcao da mesma loja cai na mesma chave. */
    static final int REVERSE_CACHE_DECIMALS = 4;
    private static final int REVERSE_CACHE_MAX_ENTRIES = 1_000;
    private static final Duration REVERSE_CACHE_TTL = Duration.ofHours(24);
    /**
     * O "nao achei" tambem fica guardado, por menos tempo (requisicoes-lentas, 09/10: o 404 do
     * reverso levava 1,8 s — a pausa do Nominatim mais a ida e volta — e o mesmo ponto pagava de novo).
     */
    static final Duration REVERSE_MISS_TTL = Duration.ofHours(1);
    /** Teto do Retry-After aceito: um valor absurdo do servidor nao pode desligar o geocoding por dias. */
    private static final Duration MAX_COOLDOWN = Duration.ofHours(1);

    private final RestClient restClient;
    private final String brasilApiUrl;
    private final String nominatimUrl;
    private final String nominatimReverseUrl;
    private final String userAgent;
    private final Duration defaultCooldown;
    private final Clock clock;

    private long lastNominatimCallAt = 0L;
    private final ReentrantLock nominatimLock = new ReentrantLock();

    /** Pausa aberta por um 429/403 do Nominatim; {@code null} = liberado. */
    private volatile Instant nominatimBlockedUntil;

    /** {@code result} null = o Nominatim respondeu e nao havia endereco ali. */
    private record CachedReverse(ReverseGeocodeResult result, Instant expiresAt) {
    }

    /** LRU pequeno em memoria — o reverso so e chamado na tela de cadastro do endereco. */
    private final Map<String, CachedReverse> reverseCache =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedReverse> eldest) {
                    return size() > REVERSE_CACHE_MAX_ENTRIES;
                }
            };

    @Autowired
    public HttpGeocoder(
            @Value("${vupth.geocoding.brasilapi.url:https://brasilapi.com.br/api/cep/v2}") String brasilApiUrl,
            @Value("${vupth.geocoding.nominatim.url:https://nominatim.openstreetmap.org/search}") String nominatimUrl,
            @Value("${vupth.geocoding.nominatim.reverse-url:https://nominatim.openstreetmap.org/reverse}") String nominatimReverseUrl,
            @Value("${vupth.geocoding.user-agent:vupth-backend/1.0 (contato: no-reply@vupth.app)}") String userAgent,
            @Value("${vupth.geocoding.timeout-seconds:5}") int timeoutSeconds,
            @Value("${vupth.geocoding.nominatim.cooldown-seconds:120}") int cooldownSeconds) {

        this(restClientWithTimeout(timeoutSeconds), brasilApiUrl, nominatimUrl, nominatimReverseUrl, userAgent,
                Duration.ofSeconds(cooldownSeconds), Clock.systemUTC());
    }

    /** Mantido para os testes antigos: pausa padrao de 120 s. */
    public HttpGeocoder(String brasilApiUrl, String nominatimUrl, String nominatimReverseUrl,
                        String userAgent, int timeoutSeconds) {
        this(brasilApiUrl, nominatimUrl, nominatimReverseUrl, userAgent, timeoutSeconds, 120);
    }

    /** Testes: RestClient com servidor falso e relogio controlado. */
    HttpGeocoder(RestClient restClient, String brasilApiUrl, String nominatimUrl, String nominatimReverseUrl,
                 String userAgent, Duration defaultCooldown, Clock clock) {
        this.restClient = restClient;
        this.brasilApiUrl = brasilApiUrl;
        this.nominatimUrl = nominatimUrl;
        this.nominatimReverseUrl = nominatimReverseUrl;
        this.userAgent = userAgent;
        this.defaultCooldown = defaultCooldown;
        this.clock = clock;
    }

    private static RestClient restClientWithTimeout(int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Optional<Instant> unavailableUntil() {
        Instant until = nominatimBlockedUntil;
        return until != null && until.isAfter(clock.instant()) ? Optional.of(until) : Optional.empty();
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

    /**
     * Reverso (fatia A2): coordenada -> endereco por extenso, via Nominatim {@code /reverse}. Nao ha
     * equivalente na BrasilAPI (ela so vai de CEP para coordenada), entao aqui e via unica.
     *
     * <p>Consome a MESMA cota do 1 req/s da busca — por isso divide o mesmo {@link #throttleNominatim()}.
     * Diferente do geocoding do cadastro, este roda <b>dentro</b> do request do dono (ele esta olhando
     * a tela, esperando o endereco aparecer), entao o timeout do RestClient e o que segura a espera.</p>
     */
    @Override
    public Optional<ReverseGeocodeResult> reverse(Coordinates coordinates) {
        if (coordinates == null || !coordinates.isPlausible()) {
            return Optional.empty();
        }
        String cacheKey = reverseCacheKey(coordinates);
        CachedReverse cached = cachedReverse(cacheKey);
        if (cached != null) {
            log.info("[Geocoding] reverso de {},{} veio do cache", coordinates.latitude(), coordinates.longitude());
            return Optional.ofNullable(cached.result());
        }
        if (inCooldown("reverso")) {
            return Optional.empty();
        }
        try {
            throttleNominatim();

            String uri = UriComponentsBuilder.fromUriString(nominatimReverseUrl)
                    .queryParam("format", "jsonv2")
                    .queryParam("addressdetails", 1)
                    .queryParam("lat", coordinates.latitude())
                    .queryParam("lon", coordinates.longitude())
                    .encode()
                    .toUriString();

            Map<?, ?> body = restClient.get()
                    .uri(uri)
                    .header("User-Agent", userAgent)
                    .retrieve()
                    .body(Map.class);

            if (body == null || !(body.get("address") instanceof Map<?, ?> address)) {
                log.info("[Geocoding] reverso nao encontrou endereco em {},{}",
                        coordinates.latitude(), coordinates.longitude());
                putCachedReverse(cacheKey, null, REVERSE_MISS_TTL);
                return Optional.empty();
            }

            ReverseGeocodeResult result = new ReverseGeocodeResult(
                    text(address.get("road")),
                    text(address.get("house_number")),
                    // O OSM nomeia o bairro de tres jeitos conforme a cidade — o primeiro que existir serve.
                    firstNonNull(address.get("suburb"), address.get("neighbourhood"), address.get("city_district")),
                    firstNonNull(address.get("city"), address.get("town"), address.get("village"),
                            address.get("municipality")),
                    toUf(address.get("ISO3166-2-lvl4"), address.get("state")),
                    toZipCode(address.get("postcode")),
                    text(body.get("display_name")));

            log.info("[Geocoding] reverso resolveu {},{} -> {}",
                    coordinates.latitude(), coordinates.longitude(), result.displayName());
            putCachedReverse(cacheKey, result, REVERSE_CACHE_TTL);
            return Optional.of(result);

        } catch (HttpClientErrorException e) {
            openCooldownIfBlocked(e);
            log.warn("[Geocoding] reverso falhou para {},{}: {}",
                    coordinates.latitude(), coordinates.longitude(), e.toString());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[Geocoding] reverso falhou para {},{}: {}",
                    coordinates.latitude(), coordinates.longitude(), e.toString());
            return Optional.empty();
        }
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
        if (inCooldown("busca")) {
            return Optional.empty();
        }
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

        } catch (HttpClientErrorException e) {
            openCooldownIfBlocked(e);
            log.warn("[Geocoding] Nominatim falhou para '{}': {}", query.street(), e.toString());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[Geocoding] Nominatim falhou para '{}': {}", query.street(), e.toString());
            return Optional.empty();
        }
    }

    /**
     * Segura a chamada para nao passar de 1 req/s no Nominatim. O geocoding roda numa unica thread de
     * fundo, entao dormir aqui nao atrasa request nenhum de usuario — so espaca a fila.
     */
    private void throttleNominatim() throws InterruptedException {
        nominatimLock.lock(); // nao synchronized: dormir num monitor prende a portadora da thread virtual
        try {
            long since = System.currentTimeMillis() - lastNominatimCallAt;
            if (since < NOMINATIM_MIN_INTERVAL_MS) {
                Thread.sleep(NOMINATIM_MIN_INTERVAL_MS - since);
            }
            lastNominatimCallAt = System.currentTimeMillis();
        } finally {
            nominatimLock.unlock();
        }
    }

    private boolean inCooldown(String what) {
        Optional<Instant> until = unavailableUntil();
        until.ifPresent(u -> log.info("[Geocoding] {} pulado: Nominatim em pausa ate {} (429)", what, u));
        return until.isPresent();
    }

    /**
     * 429 = passou do limite; 403 = a policy bloqueou o cliente. Nos dois, insistir so prolonga o
     * bloqueio — abre a pausa pelo Retry-After (segundos) ou pelo padrao configurado.
     */
    private void openCooldownIfBlocked(HttpClientErrorException e) {
        if (e.getStatusCode().value() != HttpStatus.TOO_MANY_REQUESTS.value()
                && e.getStatusCode().value() != HttpStatus.FORBIDDEN.value()) {
            return;
        }
        Duration pause = defaultCooldown;
        String retryAfter = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
        if (retryAfter != null) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                if (seconds > 0) {
                    pause = Duration.ofSeconds(seconds);
                }
            } catch (NumberFormatException ignored) {
                // Retry-After em data HTTP: fica o padrao.
            }
        }
        if (pause.compareTo(MAX_COOLDOWN) > 0) {
            pause = MAX_COOLDOWN;
        }
        Instant until = clock.instant().plus(pause);
        nominatimBlockedUntil = until;
        log.warn("[Geocoding] Nominatim respondeu {} — pausa ate {} ({} s), sem novos pedidos ate la",
                e.getStatusCode().value(), until, pause.toSeconds());
    }

    static String reverseCacheKey(Coordinates coordinates) {
        String format = "%." + REVERSE_CACHE_DECIMALS + "f,%." + REVERSE_CACHE_DECIMALS + "f";
        return String.format(Locale.ROOT, format, coordinates.latitude(), coordinates.longitude());
    }

    /** A entrada viva do cache, ou null (nunca consultado ou vencido). */
    private CachedReverse cachedReverse(String key) {
        synchronized (reverseCache) {
            CachedReverse entry = reverseCache.get(key);
            if (entry == null) {
                return null;
            }
            if (!entry.expiresAt().isAfter(clock.instant())) {
                reverseCache.remove(key);
                return null;
            }
            return entry;
        }
    }

    private void putCachedReverse(String key, ReverseGeocodeResult result, Duration ttl) {
        synchronized (reverseCache) {
            reverseCache.put(key, new CachedReverse(result, clock.instant().plus(ttl)));
        }
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

    private String text(Object value) {
        if (!(value instanceof String string) || string.isBlank()) {
            return null;
        }
        return string.trim();
    }

    private String firstNonNull(Object... values) {
        for (Object value : values) {
            String text = text(value);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    /**
     * {@code Address.state} guarda 2 letras, mas o Nominatim escreve o estado por extenso ("Rio de
     * Janeiro"). A UF vem de graca no codigo ISO ({@code "BR-RJ"}); sem ele, so devolvemos o texto se
     * ele ja for uma sigla de 2 letras — chutar a partir do nome sairia errado.
     */
    private String toUf(Object isoCode, Object stateName) {
        String iso = text(isoCode);
        if (iso != null && iso.matches("(?i)BR-[A-Z]{2}")) {
            return iso.substring(3).toUpperCase();
        }
        String name = text(stateName);
        return name != null && name.matches("[A-Za-z]{2}") ? name.toUpperCase() : null;
    }

    /** O Nominatim escreve o CEP com hifen ("21012-000"); a base guarda 8 digitos puros. */
    private String toZipCode(Object value) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        String digits = text.replaceAll("\\D", "");
        return digits.matches("\\d{8}") ? digits : null;
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
