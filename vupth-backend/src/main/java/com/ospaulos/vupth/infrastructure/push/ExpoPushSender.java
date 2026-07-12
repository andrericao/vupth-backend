package com.ospaulos.vupth.infrastructure.push;

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

/**
 * Envia push via Expo Push API (backlog #1). Ativo por padrao; desligado nos testes/staging com
 * {@code vupth.push.provider=none} (cai no {@link NoOpPushSender}).
 *
 * <p>POST {@code https://exp.host/--/api/v2/push/send} com um array de mensagens
 * {@code [{to, title, body, data, sound}]}. Best-effort: qualquer falha e logada e engolida.
 * Expo aceita ate 100 mensagens por request — enviamos em lotes.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vupth.push", name = "provider", havingValue = "expo", matchIfMissing = true)
public class ExpoPushSender implements PushSender {

    private static final int EXPO_BATCH_SIZE = 100;

    private final RestClient restClient;
    private final String expoUrl;
    private final String accessToken;

    public ExpoPushSender(
            @Value("${vupth.push.expo.url:https://exp.host/--/api/v2/push/send}") String expoUrl,
            @Value("${vupth.push.expo.access-token:}") String accessToken) {
        this.expoUrl = expoUrl;
        this.accessToken = accessToken;
        this.restClient = RestClient.create();
    }

    @Override
    public void send(List<String> tokens, String title, String body, Map<String, Object> data) {
        if (tokens == null || tokens.isEmpty()) {
            return;
        }
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
            if (data != null && !data.isEmpty()) {
                msg.put("data", data);
            }
            messages.add(msg);
        }
        if (messages.isEmpty()) {
            return;
        }

        for (int i = 0; i < messages.size(); i += EXPO_BATCH_SIZE) {
            List<Map<String, Object>> batch = messages.subList(i, Math.min(i + EXPO_BATCH_SIZE, messages.size()));
            try {
                var request = restClient.post()
                        .uri(expoUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON);
                if (accessToken != null && !accessToken.isBlank()) {
                    request = request.header("Authorization", "Bearer " + accessToken);
                }
                request.body(batch).retrieve().toBodilessEntity();
                log.info("[PUSH] Expo enviou {} mensagem(ns) title='{}'", batch.size(), title);
            } catch (Exception e) {
                log.warn("[PUSH] falha ao enviar lote Expo ({} msgs) title='{}': {}",
                        batch.size(), title, e.getMessage());
            }
        }
    }
}
