package com.ospaulos.vupth.infrastructure.asaas;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * O cliente do Asaas SANDBOX (testes-asaas-sandbox, fatia 1): cria o cliente de teste e uma cobranca. Desligado sem a
 * {@code ASAAS_API_KEY} ou fora do sandbox ({@link AsaasProperties#clientEnabled()}). Endpoints da API v3: {@code
 * GET/POST /customers}, {@code POST /payments}; a chave vai no cabecalho {@code access_token}. Os campos vem da documentacao
 * publica e os nomes exatos ficam "a confirmar" na primeira cobranca de verdade no sandbox.
 */
@Slf4j
@Component
public class AsaasClient {

    static final String TEST_CUSTOMER_REF = "vupth-sandbox";
    /** CPF de teste valido (so digitos), usado se o ADMIN nao mandar outro. */
    static final String DEFAULT_TEST_CPF = "24971563792";

    private final AsaasProperties properties;
    private final RestClient rest;

    @org.springframework.beans.factory.annotation.Autowired
    public AsaasClient(AsaasProperties properties) {
        this(properties, timeoutClient());
    }

    /** Testes: um RestClient apontado para um servidor falso. */
    public AsaasClient(AsaasProperties properties, RestClient rest) {
        this.properties = properties;
        this.rest = rest;
    }

    private static RestClient timeoutClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        return RestClient.builder().requestFactory(factory).build();
    }

    public record Charge(String id, String status, BigDecimal value, String billingType, String invoiceUrl,
                         String externalReference) {}

    public boolean enabled() {
        return properties.clientEnabled();
    }

    /** O cliente de teste do sandbox: acha o que ja existe ou cria um com o CPF dado (ou o de teste). */
    public String ensureCustomer(String cpfCnpj) {
        requireEnabled();
        JsonNode found = get("/customers?externalReference=" + TEST_CUSTOMER_REF);
        JsonNode data = found == null ? null : found.get("data");
        if (data != null && data.isArray() && !data.isEmpty() && data.get(0).hasNonNull("id")) {
            return data.get(0).get("id").asText();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "VUPTH sandbox");
        body.put("cpfCnpj", cpfCnpj == null || cpfCnpj.isBlank() ? DEFAULT_TEST_CPF : cpfCnpj.replaceAll("\\D", ""));
        body.put("externalReference", TEST_CUSTOMER_REF);
        JsonNode created = post("/customers", body);
        if (created == null || !created.hasNonNull("id")) {
            throw new AsaasException(HttpStatus.BAD_GATEWAY, "ASAAS_BAD_RESPONSE", "O Asaas nao devolveu o cliente criado.");
        }
        return created.get("id").asText();
    }

    /** Cria a cobranca (vence amanha). {@code billingType}: PIX, BOLETO, CREDIT_CARD ou UNDEFINED. */
    public Charge createCharge(String customerId, BigDecimal value, String billingType, String externalReference,
                               String description) {
        requireEnabled();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customer", customerId);
        body.put("billingType", billingType);
        body.put("value", value);
        body.put("dueDate", LocalDate.now().plusDays(1).toString());
        if (externalReference != null && !externalReference.isBlank()) {
            body.put("externalReference", externalReference);
        }
        if (description != null && !description.isBlank()) {
            body.put("description", description);
        }
        JsonNode created = post("/payments", body);
        if (created == null || !created.hasNonNull("id")) {
            throw new AsaasException(HttpStatus.BAD_GATEWAY, "ASAAS_BAD_RESPONSE", "O Asaas nao devolveu a cobranca criada.");
        }
        return new Charge(created.get("id").asText(), text(created, "status"),
                created.hasNonNull("value") ? created.get("value").decimalValue() : value,
                text(created, "billingType"), text(created, "invoiceUrl"), text(created, "externalReference"));
    }

    /** O saldo disponivel da conta ({@code GET /finance/balance}); o Painel compara com o que o app deve (saldo-do-app-e-bonificacao). */
    public BigDecimal balance() {
        requireEnabled();
        JsonNode body = get("/finance/balance");
        if (body == null || !body.hasNonNull("balance")) {
            throw new AsaasException(HttpStatus.BAD_GATEWAY, "ASAAS_BAD_RESPONSE", "O Asaas nao devolveu o saldo.");
        }
        return body.get("balance").decimalValue();
    }

    private void requireEnabled() {
        if (!properties.clientEnabled()) {
            throw new AsaasException(HttpStatus.SERVICE_UNAVAILABLE, "ASAAS_SANDBOX_DISABLED",
                    "O sandbox do Asaas esta desligado: falta a ASAAS_API_KEY, ou a ASAAS_BASE_URL nao e de sandbox.");
        }
    }

    private JsonNode get(String path) {
        try {
            return rest.get().uri(properties.baseUrl() + path).header("access_token", properties.apiKey())
                    .retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            throw failure(e);
        }
    }

    private JsonNode post(String path, Object body) {
        try {
            return rest.post().uri(properties.baseUrl() + path).header("access_token", properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            throw failure(e);
        }
    }

    private static AsaasException failure(RestClientException e) {
        if (e instanceof RestClientResponseException response) {
            log.warn("[Asaas] resposta {} do sandbox: {}", response.getStatusCode(), response.getResponseBodyAsString());
            return new AsaasException(HttpStatus.BAD_GATEWAY, "ASAAS_REJECTED",
                    "O Asaas recusou o pedido (" + response.getStatusCode().value() + "): " + response.getResponseBodyAsString());
        }
        log.warn("[Asaas] sandbox fora do ar: {}", e.getMessage());
        return new AsaasException(HttpStatus.BAD_GATEWAY, "ASAAS_UNREACHABLE", "Nao consegui falar com o Asaas.");
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
