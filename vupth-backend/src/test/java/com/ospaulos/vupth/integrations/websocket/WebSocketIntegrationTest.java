package com.ospaulos.vupth.integrations.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.websocket.dto.QueueNotification;
import com.ospaulos.vupth.infrastructure.websocket.QueueWebSocketPublisher;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests — WebSocket (STOMP).
 *
 * Cobre:
 *  - Broadcast da fila ao vivo: subscriber anonimo em /topic/establishment/{id}/queue
 *    recebe o snapshot quando broadcastQueue e disparado.
 *  - Notificacao pessoal: subscriber autenticado (token no CONNECT) em
 *    /user/queue/notifications recebe a mensagem direcionada ao seu userId.
 */
@Slf4j
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebSocketIntegrationTest {

    @LocalServerPort int port;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired QueueWebSocketPublisher queuePublisher;

    private final Faker faker = new Faker();

    private WebSocketStompClient newStompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        return client;
    }

    /** Captura o primeiro frame recebido como Map. */
    private static class CapturingHandler implements StompFrameHandler {
        final CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return Map.class;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void handleFrame(StompHeaders headers, Object payload) {
            future.complete((Map<String, Object>) payload);
        }
    }

    @Test
    void broadcastQueue_reachesAnonymousSubscriber() throws Exception {
        // setup: cria um establishment real (broadcastQueue reconstroi o snapshot dele)
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, user.token());
        UUID establishmentId = UUID.fromString(estab.id());
        log.info("[WS-IT] broadcast — establishment={}", establishmentId);

        StompSession session = newStompClient()
                .connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);

        CapturingHandler handler = new CapturingHandler();
        session.subscribe("/topic/establishment/" + establishmentId + "/queue", handler);
        Thread.sleep(300); // garante a subscription registrada no broker

        queuePublisher.broadcastQueue(establishmentId);

        Map<String, Object> payload = handler.future.get(5, TimeUnit.SECONDS);
        assertThat(payload).isNotNull();
        assertThat(payload.get("id")).isEqualTo(establishmentId.toString());
        assertThat(payload).containsKey("professionals");

        session.disconnect();
    }

    @Test
    void personalNotification_reachesAuthenticatedUser() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        UUID userId = UUID.fromString(user.id());
        log.info("[WS-IT] personal — userId={}", userId);

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", user.token()); // já vem com "Bearer "

        StompSession session = newStompClient()
                .connectAsync("ws://localhost:" + port + "/ws",
                        new WebSocketHttpHeaders(), connectHeaders,
                        new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);

        CapturingHandler handler = new CapturingHandler();
        session.subscribe("/user/queue/notifications", handler);
        Thread.sleep(300);

        queuePublisher.notifyClient(userId, QueueNotification.of(
                "ATTENDANCE_STARTED", UUID.randomUUID(), UUID.randomUUID(), "É a sua vez!"));

        Map<String, Object> payload = handler.future.get(5, TimeUnit.SECONDS);
        assertThat(payload).isNotNull();
        assertThat(payload.get("type")).isEqualTo("ATTENDANCE_STARTED");
        assertThat(payload.get("message")).isEqualTo("É a sua vez!");

        session.disconnect();
    }
}
