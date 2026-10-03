package com.ospaulos.vupth.infrastructure.websocket;

import com.ospaulos.vupth.infrastructure.security.jwt.converter.JwtAuthenticationConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Autentica a sessao STOMP no frame CONNECT lendo o header Authorization.
 *
 * - Token valido → principal cujo getName() = userId (subject do JWT),
 *   permitindo convertAndSendToUser(userId, ...) para notificacoes pessoais.
 * - Sem token → conexao anonima (so topicos publicos como /topic/establishment/{id}/queue).
 * - Token invalido ou vencido → depende de {@code vupth.ws.reject-invalid-token} (BAK-5 · S14/B8):
 *   {@code true} RECUSA o CONNECT (frame ERROR {@code INVALID_TOKEN}), para o app renovar o token e
 *   reconectar — anonimo, o canal pessoal ficava mudo sem erro em ponta nenhuma. {@code false}
 *   (default) segue anonimo, como antes: o Client ainda nao renova o token antes de reconectar
 *   (C16) e, recusado, entraria em loop de reconexao e perderia tambem os topicos publicos.
 *
 * Reusa o JwtDecoder (RSA) e o JwtAuthenticationConverter ja existentes.
 */
@Slf4j
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    /** Mensagem do frame ERROR — o app reconhece e renova o token antes de reconectar. */
    public static final String INVALID_TOKEN = "INVALID_TOKEN";

    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter jwtAuthenticationConverter;
    private final boolean rejectInvalidToken;

    public StompAuthChannelInterceptor(
            JwtDecoder jwtDecoder,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            @Value("${vupth.ws.reject-invalid-token:false}") boolean rejectInvalidToken) {
        this.jwtDecoder = jwtDecoder;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
        this.rejectInvalidToken = rejectInvalidToken;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("[WS] CONNECT anonimo (sem token) — apenas topicos publicos");
            return message;
        }

        String token = authHeader.substring(7);
        try {
            Jwt jwt = jwtDecoder.decode(token);
            AbstractAuthenticationToken converted = jwtAuthenticationConverter.convert(jwt);
            // Principal.getName() = userId, para roteamento de mensagens pessoais por userId
            var auth = new UsernamePasswordAuthenticationToken(
                    jwt.getSubject(),
                    null,
                    converted != null ? converted.getAuthorities() : null);
            accessor.setUser(auth);
            log.info("[WS] CONNECT autenticado — userId={}", jwt.getSubject());
        } catch (Exception e) {
            if (rejectInvalidToken) {
                log.warn("[WS] CONNECT com token invalido — RECUSADO: {}", e.getMessage());
                throw new MessageDeliveryException(message, INVALID_TOKEN, e);
            }
            log.warn("[WS] CONNECT com token invalido — seguindo anonimo: {}", e.getMessage());
        }
        return message;
    }
}
