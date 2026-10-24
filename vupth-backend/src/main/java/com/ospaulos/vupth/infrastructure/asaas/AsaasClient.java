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
import java.util.List;
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

    /** Sandbox ligado (chave + URL de sandbox): so as rotas de teste do ADMIN usam esta. */
    public boolean enabled() {
        return properties.clientEnabled();
    }

    /** Ha chave configurada (sandbox ou real): o fluxo normal pode cobrar quando a conexao estiver ligada. */
    public boolean configured() {
        return properties.keyConfigured();
    }

    /** As rotas de teste do ADMIN nunca cobram no real: exigem o sandbox. */
    public void requireSandbox() {
        requireEnabled();
    }

    /** Quem paga no Asaas: a referencia estavel (uma por pessoa), o nome e o e-mail do cadastro (email-do-cliente-no-asaas). */
    public record Party(String reference, String name, String email) {
        static final Party GENERIC = new Party(TEST_CUSTOMER_REF, "VUPTH sandbox", null);
    }

    /** O cliente do Asaas e se ele tem e-mail (o e-mail em si nunca sai daqui nem vai para o log). */
    public record Customer(String id, boolean hasEmail) {}

    /** O cliente de teste generico do sandbox (sem e-mail): acha o que ja existe ou cria um com o CPF dado (ou o de teste). */
    public String ensureCustomer(String cpfCnpj) {
        return ensureCustomer(Party.GENERIC, cpfCnpj).id();
    }

    /**
     * O cliente da pessoa (email-do-cliente-no-asaas): acha pela referencia ou cria com o nome, o e-mail do cadastro e o CPF/CNPJ
     * dado (ou o de teste, no sandbox). O Asaas recusa cartao sem e-mail do titular; por isso, se o cliente ja existe sem e-mail e
     * ha um e-mail do cadastro, ele e atualizado. Nada do que a pessoa digita e pedido de novo.
     */
    public Customer ensureCustomer(Party party, String cpfCnpj) {
        requireKey();
        String email = party.email() == null || party.email().isBlank() ? null : party.email().trim();
        JsonNode found = get("/customers?externalReference=" + party.reference());
        JsonNode data = found == null ? null : found.get("data");
        if (data != null && data.isArray() && !data.isEmpty() && data.get(0).hasNonNull("id")) {
            JsonNode existing = data.get(0);
            String id = existing.get("id").asText();
            boolean has = text(existing, "email") != null;
            if (email != null && !email.equalsIgnoreCase(text(existing, "email"))) {
                put("/customers/" + id, Map.of("email", email));
                has = true;
            }
            return new Customer(id, has);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", party.name() == null || party.name().isBlank() ? "VUPTH" : party.name().trim());
        if (email != null) {
            body.put("email", email);
        }
        body.put("cpfCnpj", cpfCnpj == null || cpfCnpj.isBlank() ? DEFAULT_TEST_CPF : cpfCnpj.replaceAll("\\D", ""));
        body.put("externalReference", party.reference());
        JsonNode created = post("/customers", body);
        if (created == null || !created.hasNonNull("id")) {
            throw new AsaasException(HttpStatus.BAD_GATEWAY, "ASAAS_BAD_RESPONSE", "O Asaas nao devolveu o cliente criado.");
        }
        return new Customer(created.get("id").asText(), email != null);
    }

    /** Uma parte da cobranca que vai direto para a carteira (walletId) de uma subconta: o valor fixo, em reais. */
    public record Split(String walletId, BigDecimal fixedValue) {}

    /** A subconta criada: o id da conta e a carteira ({@code walletId}) que recebe a divisao. A chave de API dela nao e guardada. */
    public record Subaccount(String id, String walletId) {}

    /**
     * Cria a subconta do profissional ({@code POST /accounts}). Os campos vem da documentacao publica do Asaas e os nomes exatos
     * ficam "a confirmar" na primeira rodada no sandbox; a resposta traz {@code id}, {@code walletId} e {@code apiKey} (esta ultima
     * descartada aqui).
     */
    public Subaccount createSubaccount(Map<String, Object> body) {
        requireKey();
        JsonNode created = post("/accounts", body);
        if (created == null || !created.hasNonNull("id") || !created.hasNonNull("walletId")) {
            throw new AsaasException(HttpStatus.BAD_GATEWAY, "ASAAS_BAD_RESPONSE", "O Asaas nao devolveu a subconta criada.");
        }
        return new Subaccount(created.get("id").asText(), created.get("walletId").asText());
    }

    public Charge createCharge(String customerId, BigDecimal value, String billingType, String externalReference,
                               String description) {
        return createCharge(customerId, value, billingType, externalReference, description, List.of());
    }

    /** Cria a cobranca (vence amanha). {@code billingType}: PIX, BOLETO, CREDIT_CARD ou UNDEFINED. Com {@code splits}, divide ao receber. */
    public Charge createCharge(String customerId, BigDecimal value, String billingType, String externalReference,
                               String description, List<Split> splits) {
        requireKey();
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
        if (splits != null && !splits.isEmpty()) {
            body.put("split", splits.stream().map(sp -> {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("walletId", sp.walletId());
                one.put("fixedValue", sp.fixedValue());
                return one;
            }).toList());
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
        requireKey();
        JsonNode body = get("/finance/balance");
        if (body == null || !body.hasNonNull("balance")) {
            throw new AsaasException(HttpStatus.BAD_GATEWAY, "ASAAS_BAD_RESPONSE", "O Asaas nao devolveu o saldo.");
        }
        return body.get("balance").decimalValue();
    }

    /** O QR Pix de uma cobranca PIX: {@code GET /payments/{id}/pixQrCode} → {@code payload} (copia e cola) e {@code encodedImage} (base64). */
    public PixQr pixQrCode(String paymentId) {
        requireKey();
        JsonNode body = get("/payments/" + paymentId + "/pixQrCode");
        if (body == null || !body.hasNonNull("payload")) {
            throw new AsaasException(HttpStatus.BAD_GATEWAY, "ASAAS_BAD_RESPONSE", "O Asaas nao devolveu o QR Pix.");
        }
        return new PixQr(body.get("payload").asText(), text(body, "encodedImage"));
    }

    public record PixQr(String payload, String encodedImage) {}

    private void requireKey() {
        if (!properties.keyConfigured()) {
            throw new AsaasException(HttpStatus.SERVICE_UNAVAILABLE, "ASAAS_NOT_CONFIGURED", "Falta a ASAAS_API_KEY.");
        }
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

    private JsonNode put(String path, Object body) {
        try {
            return rest.put().uri(properties.baseUrl() + path).header("access_token", properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            throw failure(e);
        }
    }

    private static final java.util.regex.Pattern EMAIL = java.util.regex.Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");

    /** Nenhum e-mail vai ao log nem a mensagem de erro (email-do-cliente-no-asaas). */
    public static String maskEmails(String text) {
        return text == null ? null : EMAIL.matcher(text).replaceAll("***@***");
    }

    private static AsaasException failure(RestClientException e) {
        if (e instanceof RestClientResponseException response) {
            String body = maskEmails(response.getResponseBodyAsString());
            log.warn("[Asaas] resposta {} do sandbox: {}", response.getStatusCode(), body);
            return new AsaasException(HttpStatus.BAD_GATEWAY, "ASAAS_REJECTED",
                    "O Asaas recusou o pedido (" + response.getStatusCode().value() + "): " + body);
        }
        log.warn("[Asaas] sandbox fora do ar: {}", e.getMessage());
        return new AsaasException(HttpStatus.BAD_GATEWAY, "ASAAS_UNREACHABLE", "Nao consegui falar com o Asaas.");
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
