package com.ospaulos.vupth.infrastructure.websocket;

import com.ospaulos.vupth.infrastructure.security.jwt.converter.JwtAuthenticationConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
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
 * - Sem token / token invalido → conexao anonima (so topicos publicos como
 *   /topic/establishment/{id}/queue).
 *
 * Reusa o JwtDecoder (RSA) e o JwtAuthenticationConverter ja existentes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter jwtAuthenticationConverter;

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
            log.warn("[WS] CONNECT com token invalido — seguindo anonimo: {}", e.getMessage());
        }
        return message;
    }
}
