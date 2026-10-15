package com.ospaulos.vupth.infrastructure.push;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Envia push via Expo Push API (backlog #1). Ativo por padrao; desligado nos testes/staging com
 * {@code vupth.push.provider=none} (cai no {@link NoOpPushSender}).
 *
 * <p>POST {@code https://exp.host/--/api/v2/push/send} com um array de mensagens
 * {@code [{to, title, body, data, sound, priority, channelId, categoryId?}]}. Best-effort: qualquer falha e logada e engolida.
 * Expo aceita ate 100 mensagens por request — enviamos em lotes.</p>
 *
 * <p><b>Backlog #28 — a resposta deixou de ser descartada.</b> Antes o envio terminava em
 * {@code .toBodilessEntity()} e logava {@code "Expo enviou N mensagem(ns)"} <i>incondicionalmente</i>.
 * Duas consequencias: token morto nunca saia da base (so o logout explicito o removia), e o log
 * afirmava sucesso mesmo com todos os tickets em erro — o que tornava impossivel distinguir, num QA
 * de push no aparelho, "o backend nao mandou" de "a Expo recusou". Agora o corpo e lido, cada ticket
 * e logado individualmente e os recusados sobem para o {@link PushNotificationPublisher} decidir.</p>
 *
 * <p><b>O HTTP 200 e do envelope, nao das mensagens.</b> A Expo responde 200 e devolve
 * {@code data[i].status = "ok" | "error"} <i>por mensagem</i>, na mesma ordem do lote enviado — e o
 * ticket nao repete o token, entao a correspondencia e posicional. Um lote inteiramente recusado
 * ainda chega como 200.</p>
 *
 * <p><b>Segunda etapa (receipts) — recibos-da-expo, 10/10.</b> Ticket {@code ok} significa "a Expo
 * aceitou e vai repassar a APNs/FCM", nao "o aparelho recebeu". O {@code ticket.id} volta no
 * {@link PushTicket}; o {@link PushReceiptChecker} consulta {@code /push/getReceipts} ~15 min depois
 * ({@link #fetchReceipts}).</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vupth.push", name = "provider", havingValue = "expo", matchIfMissing = true)
public class ExpoPushSender implements PushSender {

    private static final int EXPO_BATCH_SIZE = 100;

    /**
     * Canal Android combinado em 02/10, com importancia HIGH, que os dois apps criam (Partner 9fdc24c,
     * Client 1b3a3845). Precisa existir no aparelho: app ainda sem a versao nova cai no canal padrao do
     * Android. Troca por {@code vupth.push.expo.channel-id}.
     */
    static final String DEFAULT_CHANNEL_ID = "alertas";

    /**
     * Categoria que os dois apps registram com o botao OK (S36, 02/10): com ela o Android mostra o OK
     * na propria notificacao, com o app fechado. Vai em toda notificacao que so informa.
     */
    static final String INFO_CATEGORY_ID = "CIENCIA";

    /**
     * Categoria do "Hora de fechar?" (S48, 15/10): o Partner registra os botoes "Fechar agora" e
     * "Continuar atendendo" (756763b), e o profissional decide na propria notificacao.
     */
    static final String CLOSING_CATEGORY_ID = "FECHAMENTO";

    /**
     * Categorias do Client (push-categorias-do-client, DEC-43; o Client registrou em 7b5276bf): "Ver minha
     * vez" leva a fila, "Ver atendimento" leva ao atendimento. Os outros tipos seguem a regra geral (CIENCIA
     * ou, nos de decisao, nenhuma). O CUT_REQUESTED vai ao Partner e fica com a CIENCIA (S40).
     */
    static final Map<String, String> CATEGORY_BY_TYPE = Map.of(
            "CLOSING_PROMPT", CLOSING_CATEGORY_ID,
            "QUEUE_NEXT_IN_LINE", "VER_FILA",
            "ATTENDANCE_PROMOTED", "VER_FILA",
            "ATTENDANCE_CANCELLED", "VER_ATENDIMENTO",
            "CUT_ACCEPTED", "VER_ATENDIMENTO",
            "CUT_REFUSED", "VER_ATENDIMENTO",
            "CUT_EXPIRED", "VER_ATENDIMENTO");

    /**
     * As que pedem decisao (aceitar/recusar, fechar ou nao) nao levam a CIENCIA. O CLOSING_PROMPT leva a
     * FECHAMENTO; as outras seguem sem categoria — o app abre nelas.
     * O CUT_REQUESTED saiu daqui (S40, 08/10): o Partner trata o pedido de corte como aviso com OK, e o
     * toque no corpo abre as Solicitacoes de corte, onde ele decide.
     */
    static final Set<String> DECISION_TYPES = Set.of(
            "CLOSING_PROMPT", "SWAP_REQUESTED", "EARLIER_TURN_OFFERED");

    /** A Expo aceita ate 1000 ids por consulta de recibos. */
    static final int RECEIPTS_BATCH_SIZE = 1000;

    private final RestClient restClient;
    private final String expoUrl;
    private final String receiptsUrl;
    private final String accessToken;
    private final String channelId;

    public ExpoPushSender(String expoUrl, String accessToken) {
        this(expoUrl, accessToken, DEFAULT_CHANNEL_ID);
    }

    public ExpoPushSender(String expoUrl, String accessToken, String channelId) {
        this(expoUrl, accessToken, channelId, receiptsUrlFor(expoUrl));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ExpoPushSender(
            @Value("${vupth.push.expo.url:https://exp.host/--/api/v2/push/send}") String expoUrl,
            @Value("${vupth.push.expo.access-token:}") String accessToken,
            @Value("${vupth.push.expo.channel-id:" + DEFAULT_CHANNEL_ID + "}") String channelId,
            @Value("${vupth.push.expo.receipts-url:}") String receiptsUrl) {
        this.expoUrl = expoUrl;
        this.receiptsUrl = receiptsUrl == null || receiptsUrl.isBlank() ? receiptsUrlFor(expoUrl) : receiptsUrl;
        this.accessToken = accessToken;
        this.channelId = channelId;
        this.restClient = RestClient.create();
    }

    /** {@code .../push/send} -> {@code .../push/getReceipts}: o mesmo host, a rota irma. */
    public static String receiptsUrlFor(String sendUrl) {
        int slash = sendUrl.lastIndexOf('/');
        return (slash < 0 ? "" : sendUrl.substring(0, slash + 1)) + "getReceipts";
    }

    @Override
    public List<PushTicket> send(List<String> tokens, String title, String body, Map<String, Object> data) {
        if (tokens == null || tokens.isEmpty()) {
            return List.of();
        }
        List<String> recipients = new ArrayList<>();
        List<Map<String, Object>> messages = new ArrayList<>();
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            Map<String, Object> msg = new HashMap<>();
            msg.put("to", token);
            msg.put("title", title);
            msg.put("body", body);
            msg.put("sound", "default");
            // Sem "high" a Expo manda com prioridade normal, e o Android em repouso (Doze) segura a
            // entrega por minutos: o aviso de "sua vez" chegava depois da vez (teste de 02/10).
            msg.put("priority", "high");
            msg.put("channelId", channelId);
            if (data != null && !data.isEmpty()) {
                msg.put("data", data);
            }
            String categoryId = categoryFor(data);
            if (categoryId != null) {
                msg.put("categoryId", categoryId);
            }
            recipients.add(token);
            messages.add(msg);
        }
        if (messages.isEmpty()) {
            return List.of();
        }

        List<PushTicket> tickets = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i += EXPO_BATCH_SIZE) {
            int end = Math.min(i + EXPO_BATCH_SIZE, messages.size());
            List<Map<String, Object>> batch = messages.subList(i, end);
            List<String> batchTokens = recipients.subList(i, end);
            tickets.addAll(sendBatch(batch, batchTokens, title));
        }
        return tickets;
    }

    private static String categoryFor(Map<String, Object> data) {
        if (data == null) {
            return INFO_CATEGORY_ID;
        }
        String type = String.valueOf(data.get("type")); // Set.of nao aceita contains(null)
        if (CATEGORY_BY_TYPE.containsKey(type)) {
            return CATEGORY_BY_TYPE.get(type);
        }
        return DECISION_TYPES.contains(type) ? null : INFO_CATEGORY_ID;
    }

    private List<PushTicket> sendBatch(List<Map<String, Object>> batch, List<String> batchTokens, String title) {
        ExpoSendResponse response;
        try {
            var request = restClient.post()
                    .uri(expoUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON);
            if (accessToken != null && !accessToken.isBlank()) {
                request = request.header("Authorization", "Bearer " + accessToken);
            }
            long start = System.nanoTime();
            response = request.body(batch).retrieve().body(ExpoSendResponse.class);
            log.info("[PUSH] lote de {} msg(s) entregue a Expo em {} ms title='{}' (priority=high channel={})",
                    batch.size(), (System.nanoTime() - start) / 1_000_000, title, channelId);
        } catch (Exception e) {
            // Rede/HTTP fora do ar: nenhum ticket existe. NAO devolvemos "erro por token" aqui — o
            // token pode estar perfeitamente vivo, e trata-lo como morto apagaria device bom.
            log.warn("[PUSH] falha ao enviar lote Expo ({} msgs) title='{}': {}",
                    batch.size(), title, e.getMessage());
            return List.of();
        }
        return readTickets(response, batchTokens, title);
    }

    /**
     * Traduz o corpo da Expo em um ticket por token. A correspondencia e <b>posicional</b>: o
     * {@code data[i]} e o recibo do {@code batchTokens.get(i)}.
     */
    private List<PushTicket> readTickets(ExpoSendResponse response, List<String> batchTokens, String title) {
        if (response == null || response.data() == null) {
            // Requisicao rejeitada inteira (ex.: credencial invalida) ou corpo inesperado: a Expo
            // devolve `errors` no lugar de `data`. Sem tickets nao ha o que atribuir a token nenhum.
            String detail = response == null || response.errors() == null
                    ? "corpo vazio ou ilegivel"
                    : response.errors().toString();
            log.warn("[PUSH] Expo nao devolveu tickets para o lote de {} msg(s) title='{}': {}",
                    batchTokens.size(), title, detail);
            return List.of();
        }

        List<ExpoSendResponse.Ticket> data = response.data();
        if (data.size() != batchTokens.size()) {
            // Nunca deve acontecer (a API e posicional e 1-para-1), mas se acontecer o pareamento
            // por indice estaria errado — e apagar o token errado e pior que nao apagar nenhum.
            log.warn("[PUSH] Expo devolveu {} ticket(s) para {} mensagem(ns) title='{}' — "
                            + "pareamento posicional abandonado, nenhum token sera desativado",
                    data.size(), batchTokens.size(), title);
            return List.of();
        }

        List<PushTicket> tickets = new ArrayList<>(data.size());
        int accepted = 0;
        for (int i = 0; i < data.size(); i++) {
            ExpoSendResponse.Ticket ticket = data.get(i);
            String token = batchTokens.get(i);
            if (ticket != null && ticket.isOk()) {
                accepted++;
                tickets.add(PushTicket.accepted(token, ticket.id()));
                continue;
            }
            String error = ticket == null || ticket.details() == null ? null : ticket.details().error();
            String message = ticket == null ? "ticket ausente" : ticket.message();
            log.warn("[PUSH] Expo RECUSOU token={} error={} title='{}': {}",
                    PushTokenMask.mask(token), error, title, message);
            tickets.add(PushTicket.rejected(token, error, message));
        }
        // O contraste com o log antigo: aqui os numeros vem do corpo, nao do tamanho do lote.
        log.info("[PUSH] Expo title='{}' — {} aceito(s), {} recusado(s) de {} enviada(s)",
                title, accepted, data.size() - accepted, data.size());
        return tickets;
    }

    /**
     * Le os recibos de {@code ticketIds} ({@code POST /push/getReceipts}, recibos-da-expo). Devolve so
     * os recibos que a Expo ja tem, por id: id que nao voltou ainda nao tem recibo (ou ja expirou, depois
     * de 24 h). Falha de rede ou corpo ilegivel devolve mapa vazio — nada se conclui, nada se apaga.
     */
    @Override
    public Map<String, PushReceipt> fetchReceipts(List<String> ticketIds) {
        if (ticketIds == null || ticketIds.isEmpty()) {
            return Map.of();
        }
        Map<String, PushReceipt> receipts = new HashMap<>();
        for (int i = 0; i < ticketIds.size(); i += RECEIPTS_BATCH_SIZE) {
            List<String> batch = ticketIds.subList(i, Math.min(i + RECEIPTS_BATCH_SIZE, ticketIds.size()));
            receipts.putAll(fetchReceiptsBatch(batch));
        }
        return receipts;
    }

    private Map<String, PushReceipt> fetchReceiptsBatch(List<String> ids) {
        ExpoReceiptsResponse response;
        try {
            var request = restClient.post()
                    .uri(receiptsUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON);
            if (accessToken != null && !accessToken.isBlank()) {
                request = request.header("Authorization", "Bearer " + accessToken);
            }
            response = request.body(Map.of("ids", ids)).retrieve().body(ExpoReceiptsResponse.class);
        } catch (Exception e) {
            log.warn("[PUSH] falha ao ler {} recibo(s) da Expo: {}", ids.size(), e.getMessage());
            return Map.of();
        }
        if (response == null || response.data() == null) {
            log.warn("[PUSH] Expo nao devolveu recibos para {} id(s): {}", ids.size(),
                    response == null || response.errors() == null ? "corpo vazio ou ilegivel" : response.errors());
            return Map.of();
        }
        Map<String, PushReceipt> receipts = new HashMap<>();
        response.data().forEach((id, r) -> {
            if (r == null) {
                return;
            }
            receipts.put(id, new PushReceipt(id, r.isOk(),
                    r.details() == null ? null : r.details().error(), r.message()));
        });
        return receipts;
    }

    /** Corpo do {@code POST /push/getReceipts}: {@code data} com um recibo por id, ou {@code errors}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExpoReceiptsResponse(Map<String, ExpoSendResponse.Ticket> data, List<Map<String, Object>> errors) {
    }

    /** Corpo do {@code POST /push/send}: {@code data} com um ticket por mensagem, ou {@code errors}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExpoSendResponse(List<Ticket> data, List<Map<String, Object>> errors) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Ticket(String status, String id, String message, Details details) {

            boolean isOk() {
                return "ok".equalsIgnoreCase(status);
            }
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Details(String error) {
        }
    }
}
