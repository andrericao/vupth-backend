package com.ospaulos.vupth.unit.asaas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ospaulos.vupth.infrastructure.asaas.AsaasClient;
import com.ospaulos.vupth.infrastructure.asaas.AsaasEvent;
import com.ospaulos.vupth.infrastructure.asaas.AsaasEventRepository;
import com.ospaulos.vupth.infrastructure.asaas.AsaasException;
import com.ospaulos.vupth.infrastructure.asaas.AsaasProperties;
import com.ospaulos.vupth.infrastructure.asaas.AsaasWebhookService;
import com.ospaulos.vupth.infrastructure.asaas.AsaasWebhookService.Result;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** testes-asaas-sandbox, fatia 1: a configuracao, o webhook e o cliente do sandbox (servidor falso do proprio JDK). */
class AsaasWebhookAndClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ─── configuracao ───

    @Test
    @DisplayName("o cliente so liga com a chave e uma URL de sandbox; o webhook so liga com o token; a URL perde a barra do fim")
    void properties() {
        assertThat(new AsaasProperties("chave", "https://api-sandbox.asaas.com/v3/", "t").clientEnabled()).isTrue();
        assertThat(new AsaasProperties("chave", "https://api-sandbox.asaas.com/v3/", "t").baseUrl())
                .isEqualTo("https://api-sandbox.asaas.com/v3");
        assertThat(new AsaasProperties("", "https://api-sandbox.asaas.com/v3", "").clientEnabled()).isFalse();
        assertThat(new AsaasProperties("chave", "https://api.asaas.com/v3", "").clientEnabled())
                .as("producao nunca liga").isFalse();
        assertThat(new AsaasProperties(null, null, null).baseUrl()).isEqualTo(AsaasProperties.DEFAULT_SANDBOX_URL);
        assertThat(new AsaasProperties(" ", " ", " ").webhookEnabled()).isFalse();
        assertThat(new AsaasProperties("k", "u-sandbox", "tok").webhookToken()).isEqualTo("tok");
        assertThat(new AsaasProperties(" k ", "u-sandbox", "t").apiKey()).isEqualTo("k");
    }

    // ─── webhook ───

    private JsonNode json(String text) throws IOException {
        return mapper.readTree(text);
    }

    private AsaasWebhookService webhook(String token, AsaasEventRepository repo) {
        return new AsaasWebhookService(new AsaasProperties("k", "https://api-sandbox.asaas.com/v3", token), repo);
    }

    @Test
    @DisplayName("webhook: sem token configurado fica desligado; token errado ou ausente e 403; corpo sem evento e invalido")
    void webhookGuards() throws Exception {
        AsaasEventRepository repo = mock(AsaasEventRepository.class);
        JsonNode body = json("{\"id\":\"evt_1\",\"event\":\"PAYMENT_RECEIVED\",\"payment\":{\"id\":\"pay_1\"}}");

        assertThat(webhook("", repo).receive("x", body)).isEqualTo(Result.DISABLED);
        assertThat(webhook("segredo", repo).receive("errado", body)).isEqualTo(Result.FORBIDDEN);
        assertThat(webhook("segredo", repo).receive(null, body)).isEqualTo(Result.FORBIDDEN);
        assertThat(webhook("segredo", repo).receive("segredo", json("{\"id\":\"evt_2\"}"))).isEqualTo(Result.INVALID);
        assertThat(webhook("segredo", repo).receive("segredo", json("{\"event\":\"  \"}"))).isEqualTo(Result.INVALID);
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("webhook: grava o evento com valor, liquido, estado e referencia; o reenvio (mesmo id) e ignorado")
    void webhookRecordsAndIgnoresResend() throws Exception {
        AsaasEventRepository repo = mock(AsaasEventRepository.class);
        JsonNode body = json("""
                {"id":"evt_9","event":"PAYMENT_RECEIVED","dateCreated":"2026-10-18 10:00:00","payment":
                 {"id":"pay_9","value":55.9,"netValue":53.91,"status":"RECEIVED","billingType":"PIX","externalReference":"att-1"}}
                """);

        assertThat(webhook("segredo", repo).receive("segredo", body)).isEqualTo(Result.OK);

        ArgumentCaptor<AsaasEvent> saved = ArgumentCaptor.forClass(AsaasEvent.class);
        verify(repo).save(saved.capture());
        AsaasEvent e = saved.getValue();
        assertThat(e.getEventId()).isEqualTo("evt_9");
        assertThat(e.getEvent()).isEqualTo("PAYMENT_RECEIVED");
        assertThat(e.getPaymentId()).isEqualTo("pay_9");
        assertThat(e.getValue()).isEqualByComparingTo("55.90");
        assertThat(e.getNetValue()).isEqualByComparingTo("53.91");
        assertThat(e.getStatus()).isEqualTo("RECEIVED");
        assertThat(e.getBillingType()).isEqualTo("PIX");
        assertThat(e.getExternalReference()).isEqualTo("att-1");
        assertThat(e.getReceivedAt()).isNotNull();

        when(repo.existsByEventId("evt_9")).thenReturn(true);
        assertThat(webhook("segredo", repo).receive("segredo", body)).isEqualTo(Result.DUPLICATE);
        verify(repo).save(any());
    }

    @Test
    @DisplayName("webhook: sem id usa evento+cobranca+data; sem objeto de cobranca ou com valores que nao sao numero grava so o evento; textos longos sao cortados")
    void webhookFallbacks() throws Exception {
        AsaasEventRepository repo = mock(AsaasEventRepository.class);
        var service = webhook("segredo", repo);

        assertThat(service.receive("segredo", json("{\"event\":\"PAYMENT_CREATED\",\"dateCreated\":\"d\",\"payment\":{\"id\":\"pay_2\",\"value\":\"x\",\"netValue\":null}}")))
                .isEqualTo(Result.OK);
        assertThat(service.receive("segredo", json("{\"event\":\"" + "E".repeat(90) + "\"}"))).isEqualTo(Result.OK);

        ArgumentCaptor<AsaasEvent> saved = ArgumentCaptor.forClass(AsaasEvent.class);
        verify(repo, org.mockito.Mockito.times(2)).save(saved.capture());
        AsaasEvent first = saved.getAllValues().get(0);
        assertThat(first.getEventId()).isEqualTo("PAYMENT_CREATED:pay_2:d");
        assertThat(first.getValue()).isNull();
        assertThat(first.getNetValue()).isNull();
        AsaasEvent second = saved.getAllValues().get(1);
        assertThat(second.getEvent()).hasSize(60);
        assertThat(second.getPaymentId()).isNull();
        assertThat(second.getValue()).isNull();
    }

    // ─── cliente do sandbox ───

    private AsaasClient client(String key) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        return new AsaasClient(new AsaasProperties(key, "http://localhost:" + server.getAddress().getPort() + "/sandbox/v3", ""),
                RestClient.create());
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    @DisplayName("cliente: desligado (sem chave) responde 503 ASAAS_SANDBOX_DISABLED e nao chama a rede")
    void clientDisabled() throws Exception {
        AsaasClient disabled = client("");

        assertThat(disabled.enabled()).isFalse();
        assertThatThrownBy(() -> disabled.ensureCustomer(null)).isInstanceOfSatisfying(AsaasException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.getCode()).isEqualTo("ASAAS_SANDBOX_DISABLED");
        });
        assertThatThrownBy(() -> disabled.createCharge("cus", new BigDecimal("10"), "PIX", null, null))
                .isInstanceOf(AsaasException.class);
    }

    @Test
    @DisplayName("cliente: acha o cliente de teste que ja existe; senao cria com o CPF dado ou o de teste; cria a cobranca com a referencia")
    void customerAndCharge() throws Exception {
        AsaasClient client = client("chave-secreta");
        List<String> calls = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        server.createContext("/sandbox/v3/customers", exchange -> {
            keys.add(exchange.getRequestHeaders().getFirst("access_token"));
            calls.add(exchange.getRequestMethod());
            if ("GET".equals(exchange.getRequestMethod())) {
                reply(exchange, 200, calls.size() == 1 ? "{\"data\":[]}" : "{\"data\":[{\"id\":\"cus_old\"}]}");
            } else {
                String sent = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(sent).contains("\"cpfCnpj\":\"24971563792\"").contains("vupth-sandbox");
                reply(exchange, 200, "{\"id\":\"cus_new\"}");
            }
        });
        server.createContext("/sandbox/v3/payments", exchange -> {
            keys.add(exchange.getRequestHeaders().getFirst("access_token"));
            String sent = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sent).contains("\"customer\":\"cus_new\"").contains("\"billingType\":\"PIX\"")
                    .contains("\"value\":55.9").contains("\"externalReference\":\"att-1\"").contains("\"dueDate\"");
            reply(exchange, 200, "{\"id\":\"pay_1\",\"status\":\"PENDING\",\"value\":55.9,\"billingType\":\"PIX\","
                    + "\"invoiceUrl\":\"https://sandbox.asaas.com/i/1\",\"externalReference\":\"att-1\"}");
        });
        server.start();

        assertThat(client.enabled()).isTrue();
        assertThat(client.ensureCustomer(null)).isEqualTo("cus_new");
        assertThat(client.ensureCustomer("249.715.637-92")).isEqualTo("cus_old");
        AsaasClient.Charge charge = client.createCharge("cus_new", new BigDecimal("55.90"), "PIX", "att-1", "teste");

        assertThat(charge.id()).isEqualTo("pay_1");
        assertThat(charge.status()).isEqualTo("PENDING");
        assertThat(charge.value()).isEqualByComparingTo("55.90");
        assertThat(charge.invoiceUrl()).isEqualTo("https://sandbox.asaas.com/i/1");
        assertThat(charge.externalReference()).isEqualTo("att-1");
        assertThat(keys).allMatch("chave-secreta"::equals);
    }

    @Test
    @DisplayName("cliente: resposta sem id, erro do Asaas e Asaas fora do ar viram ASAAS_BAD_RESPONSE, ASAAS_REJECTED e ASAAS_UNREACHABLE (502)")
    void clientFailures() throws Exception {
        AsaasClient client = client("k");
        server.createContext("/sandbox/v3/customers", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                reply(exchange, 200, "{\"data\":[]}");
            } else {
                reply(exchange, 200, "{}");
            }
        });
        server.createContext("/sandbox/v3/payments", exchange -> {
            String sent = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (sent.contains("\"value\":1")) {
                reply(exchange, 200, "{}");
            } else {
                reply(exchange, 400, "{\"errors\":[{\"description\":\"valor invalido\"}]}");
            }
        });
        server.start();

        assertThatThrownBy(() -> client.ensureCustomer(null)).isInstanceOfSatisfying(AsaasException.class,
                e -> assertThat(e.getCode()).isEqualTo("ASAAS_BAD_RESPONSE"));
        assertThatThrownBy(() -> client.createCharge("c", new BigDecimal("1"), "PIX", null, null)).isInstanceOfSatisfying(
                AsaasException.class, e -> assertThat(e.getCode()).isEqualTo("ASAAS_BAD_RESPONSE"));
        assertThatThrownBy(() -> client.createCharge("c", new BigDecimal("9"), "PIX", null, null)).isInstanceOfSatisfying(
                AsaasException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getCode()).isEqualTo("ASAAS_REJECTED");
                    assertThat(e.getMessage()).contains("400").contains("valor invalido");
                });

        server.stop(0);
        assertThatThrownBy(() -> client.ensureCustomer(null)).isInstanceOfSatisfying(AsaasException.class,
                e -> assertThat(e.getCode()).isEqualTo("ASAAS_UNREACHABLE"));
        assertThatThrownBy(() -> client.createCharge("c", new BigDecimal("9"), "PIX", null, null)).isInstanceOfSatisfying(
                AsaasException.class, e -> assertThat(e.getCode()).isEqualTo("ASAAS_UNREACHABLE"));
    }
}
