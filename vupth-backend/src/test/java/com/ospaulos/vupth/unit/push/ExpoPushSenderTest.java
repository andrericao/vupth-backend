package com.ospaulos.vupth.unit.push;

import com.ospaulos.vupth.infrastructure.push.ExpoPushSender;
import com.ospaulos.vupth.infrastructure.push.PushTicket;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests — leitura dos tickets da Expo (backlog #28).
 *
 * <p>O envio antigo terminava em {@code .toBodilessEntity()}: o corpo ia fora e o log dizia
 * "Expo enviou N mensagem(ns)" mesmo com todos os tickets em erro. Estes testes fixam o contrato
 * novo — <b>um {@link PushTicket} por token, na ordem em que os tokens foram enviados</b> — e, mais
 * importante, as situacoes em que o sender se recusa a atribuir culpa a um token, porque desativar
 * o token errado e pior do que nao desativar nenhum.</p>
 *
 * <p>A Expo e simulada por um {@link HttpServer} do proprio JDK (sem dependencia nova, restricao de
 * custo zero): o teste sobe um servidor em porta efemera e aponta o sender para ele.</p>
 */
class ExpoPushSenderTest {

    private static final String TOKEN_A = "ExponentPushToken[aaaaaaaaaaaaaaaaaaaaaa]";
    private static final String TOKEN_B = "ExponentPushToken[bbbbbbbbbbbbbbbbbbbbbb]";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Sobe uma "Expo" que responde sempre o mesmo corpo, e devolve o sender apontado para ela. */
    private ExpoPushSender senderReplying(int statusCode, String jsonBody) throws IOException {
        return senderReplying(statusCode, jsonBody, new AtomicReference<>());
    }

    private ExpoPushSender senderReplying(int statusCode, String jsonBody, AtomicReference<String> capturedBody)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/send", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = jsonBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort() + "/send";
        return new ExpoPushSender(url, "");
    }

    private List<PushTicket> send(ExpoPushSender sender, String... tokens) {
        return sender.send(List.of(tokens), "Sua vez", "Voce e o proximo", Map.of("type", "QUEUE"));
    }

    @Test
    @DisplayName("Lote aceito — um ticket ok por token, nenhum device dado como morto")
    void allAccepted() throws IOException {
        var sender = senderReplying(200, """
                {"data":[{"status":"ok","id":"t-1"},{"status":"ok","id":"t-2"}]}""");

        List<PushTicket> tickets = send(sender, TOKEN_A, TOKEN_B);

        assertThat(tickets).hasSize(2);
        assertThat(tickets).allMatch(PushTicket::ok);
        assertThat(tickets).noneMatch(PushTicket::isDeviceGone);
        assertThat(tickets.get(0).token()).isEqualTo(TOKEN_A);
        assertThat(tickets.get(1).token()).isEqualTo(TOKEN_B);
    }

    @Test
    @DisplayName("Toda mensagem sai com priority high e o channelId do app — sem isso o Android em repouso atrasa")
    void sendsHighPriorityAndChannel() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        var sender = senderReplying(200, """
                {"data":[{"status":"ok","id":"t-1"}]}""", body);

        send(sender, TOKEN_A);

        assertThat(body.get()).contains("\"priority\":\"high\"").contains("\"channelId\":\"alertas\"");
    }

    @Test
    @DisplayName("Notificacao que so informa sai com categoryId CIENCIA — o Android mostra o OK com o app fechado (S36)")
    void informationalCarriesCienciaCategory() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        var sender = senderReplying(200, """
                {"data":[{"status":"ok","id":"t-1"}]}""", body);

        sender.send(List.of(TOKEN_A), "Atendimento concluido", "Seu atendimento foi concluido.",
                Map.of("type", "ATTENDANCE_FINISHED"));

        assertThat(body.get()).contains("\"categoryId\":\"CIENCIA\"");
    }

    @Test
    @DisplayName("Notificacao que pede decisao (troca de posicao) segue sem categoria")
    void decisionHasNoCategory() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        var sender = senderReplying(200, """
                {"data":[{"status":"ok","id":"t-1"}]}""", body);

        sender.send(List.of(TOKEN_A), "Pedido de troca", "Aceite ou recuse.",
                Map.of("type", "SWAP_REQUESTED"));

        assertThat(body.get()).doesNotContain("categoryId");
    }

    @Test
    @DisplayName("Pedido de corte sai com categoryId CIENCIA — o OK aparece com o app fechado (S40)")
    void cutRequestCarriesCienciaCategory() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        var sender = senderReplying(200, """
                {"data":[{"status":"ok","id":"t-1"}]}""", body);

        sender.send(List.of(TOKEN_A), "Novo pedido de corte", "Aceite ou recuse.",
                Map.of("type", "CUT_REQUESTED"));

        assertThat(body.get()).contains("\"categoryId\":\"CIENCIA\"");
    }

    @Test
    @DisplayName("DeviceNotRegistered marca o token como morto — e so ELE, pelo indice")
    void deviceNotRegisteredIsPairedByPosition() throws IOException {
        // O ticket NAO repete o token: a correspondencia e posicional. Se o pareamento estiver
        // errado, o publisher apaga o device do usuario errado — por isso o teste manda o erro no
        // SEGUNDO token, onde uma troca de indice apareceria.
        var sender = senderReplying(200, """
                {"data":[
                  {"status":"ok","id":"t-1"},
                  {"status":"error","message":"\\"ExponentPushToken[bbb]\\" is not a registered push notification recipient device",
                   "details":{"error":"DeviceNotRegistered"}}
                ]}""");

        List<PushTicket> tickets = send(sender, TOKEN_A, TOKEN_B);

        assertThat(tickets).hasSize(2);
        assertThat(tickets.get(0).isDeviceGone()).isFalse();
        assertThat(tickets.get(1).isDeviceGone()).isTrue();
        assertThat(tickets.get(1).token()).isEqualTo(TOKEN_B);
        assertThat(tickets.get(1).error()).isEqualTo("DeviceNotRegistered");
        assertThat(tickets.get(1).message()).contains("not a registered push notification recipient device");
    }

    @Test
    @DisplayName("Erro que NAO e DeviceNotRegistered nao mata o token")
    void otherErrorsDoNotKillTheToken() throws IOException {
        // MessageTooBig e culpa da MENSAGEM, nao do aparelho: apagar o token aqui perderia um
        // device perfeitamente vivo por causa de um bug nosso de payload.
        var sender = senderReplying(200, """
                {"data":[{"status":"error","message":"Message too big","details":{"error":"MessageTooBig"}}]}""");

        List<PushTicket> tickets = send(sender, TOKEN_A);

        assertThat(tickets).hasSize(1);
        assertThat(tickets.get(0).ok()).isFalse();
        assertThat(tickets.get(0).error()).isEqualTo("MessageTooBig");
        assertThat(tickets.get(0).isDeviceGone()).isFalse();
    }

    @Test
    @DisplayName("HTTP 200 com o lote INTEIRO recusado — o envelope mente, os tickets nao")
    void httpOkWithEveryTicketInError() throws IOException {
        // Este e exatamente o caso que o log antigo reportava como "Expo enviou 2 mensagem(ns)".
        var sender = senderReplying(200, """
                {"data":[
                  {"status":"error","message":"not registered","details":{"error":"DeviceNotRegistered"}},
                  {"status":"error","message":"not registered","details":{"error":"DeviceNotRegistered"}}
                ]}""");

        List<PushTicket> tickets = send(sender, TOKEN_A, TOKEN_B);

        assertThat(tickets).hasSize(2);
        assertThat(tickets).noneMatch(PushTicket::ok);
        assertThat(tickets).allMatch(PushTicket::isDeviceGone);
    }

    @Test
    @DisplayName("Resposta com 'errors' no lugar de 'data' — nenhum ticket, nenhum token culpado")
    void requestLevelErrorYieldsNoTickets() throws IOException {
        // Credencial invalida derruba a requisicao inteira: os tokens podem estar todos vivos.
        var sender = senderReplying(400, """
                {"errors":[{"code":"UNAUTHORIZED","message":"Invalid credentials"}]}""");

        List<PushTicket> tickets = send(sender, TOKEN_A, TOKEN_B);

        assertThat(tickets).isEmpty();
    }

    @Test
    @DisplayName("Quantidade de tickets != quantidade de mensagens — abandona o pareamento")
    void mismatchedTicketCountAbortsPairing() throws IOException {
        // Nunca deveria acontecer, mas se acontecer o indice esta mentindo: e melhor manter um
        // token morto na base do que apagar o token vivo de outra pessoa.
        var sender = senderReplying(200, """
                {"data":[{"status":"error","details":{"error":"DeviceNotRegistered"}}]}""");

        List<PushTicket> tickets = send(sender, TOKEN_A, TOKEN_B);

        assertThat(tickets).isEmpty();
    }

    @Test
    @DisplayName("Expo fora do ar nao lanca e nao acusa token — push e best-effort")
    void providerDownIsSwallowed() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        int deadPort = server.getAddress().getPort();
        server.stop(0);
        server = null;
        var sender = new ExpoPushSender("http://localhost:" + deadPort + "/send", "");

        AtomicReference<List<PushTicket>> result = new AtomicReference<>();
        assertThatCode(() -> result.set(send(sender, TOKEN_A))).doesNotThrowAnyException();
        assertThat(result.get()).isEmpty();
    }

    @Test
    @DisplayName("Token nulo/em branco nao vira mensagem — o ticket segue alinhado com o que foi enviado")
    void blankTokensAreSkipped() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        var sender = senderReplying(200, """
                {"data":[{"status":"ok","id":"t-1"}]}""", body);

        List<PushTicket> tickets = sender.send(
                java.util.Arrays.asList(null, "  ", TOKEN_A), "Sua vez", "Voce e o proximo", Map.of());

        assertThat(tickets).hasSize(1);
        assertThat(tickets.get(0).token()).isEqualTo(TOKEN_A);
        assertThat(body.get()).contains(TOKEN_A);
    }

    @Test
    @DisplayName("Lista vazia ou nula nao chama a Expo")
    void emptyInputShortCircuits() {
        var sender = new ExpoPushSender("http://localhost:1/send", "");

        assertThat(sender.send(List.of(), "t", "b", Map.of())).isEmpty();
        assertThat(sender.send(null, "t", "b", Map.of())).isEmpty();
    }
}
