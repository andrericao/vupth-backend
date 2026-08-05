package com.ospaulos.vupth.infrastructure.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

/**
 * Configuracao STOMP-over-WebSocket.
 *
 * Endpoint de handshake: /ws (nativo p/ apps mobile + SockJS p/ browser).
 * Broker simples em memoria:
 *   - /topic/establishment/{id}/queue  → broadcast da fila ao vivo (publico)
 *   - /user/queue/notifications        → notificacoes pessoais (autenticado)
 *
 * @apiNote Broker em memoria atende 1 instancia (MVP). Para multi-instancia,
 *          trocar por broker externo (RabbitMQ/Redis) — ver backlog.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Heartbeat STOMP em milissegundos, nos dois sentidos: o servidor ESCREVE um frame a cada
     * {@code HEARTBEAT_MS} quando nao ha outro trafego, e ESPERA receber algo do cliente na mesma
     * cadencia (senao encerra a sessao). E exatamente o que os dois apps ja pedem no CONNECT
     * ({@code heart-beat:10000,10000}).
     */
    private static final long HEARTBEAT_MS = 10_000;

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final ObjectMapper objectMapper;

    /**
     * Scheduler PROPRIO do heartbeat do broker (uma unica tarefa em taxa fixa), instanciado aqui em vez
     * de reusar um bean compartilhado — mesma razao dos schedulers do dominio: o projeto NAO usa
     * {@code @EnableScheduling} e a thread e <b>daemon</b>, para nunca segurar o encerramento da JVM
     * (contexto de integracao cacheado + fork do surefire).
     */
    private final ThreadPoolTaskScheduler heartbeatScheduler = createHeartbeatScheduler();

    private static ThreadPoolTaskScheduler createHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); // o broker agenda UMA tarefa de heartbeat; mais threads nao ajudam
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }

    @PreDestroy
    void shutdownHeartbeatScheduler() {
        heartbeatScheduler.shutdown();
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
    }

    /**
     * <b>Heartbeat LIGADO.</b> Sem {@code setTaskScheduler} o broker simples responde
     * {@code heart-beat:0,0} no CONNECTED (medido em producao 2026-08-01) e a conexao fica sem
     * sinal de vida: quando o Render corta o socket ocioso, o app continua "conectado" sobre um
     * socket zumbi, o {@code reconnectDelay} do cliente nunca dispara e a fila para de atualizar
     * silenciosamente. Com o heartbeat, o corte vira erro de escrita/leitura -> desconexao -> o
     * cliente reconecta sozinho. O trafego periodico tambem evita o proprio corte por ociosidade.
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{HEARTBEAT_MS, HEARTBEAT_MS})
                .setTaskScheduler(heartbeatScheduler);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }

    /**
     * Usa o ObjectMapper da aplicacao (com JavaTimeModule) na serializacao das
     * mensagens, garantindo suporte a Instant/ZonedDateTime nos payloads
     * (snapshot da fila e notificacoes). Retorna true para manter os conversores
     * padrao como fallback.
     */
    @Override
    public boolean configureMessageConverters(List<MessageConverter> messageConverters) {
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);
        messageConverters.add(converter);
        return true;
    }
}
