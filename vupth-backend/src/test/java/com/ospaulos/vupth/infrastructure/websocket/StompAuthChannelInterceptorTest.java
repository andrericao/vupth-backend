package com.ospaulos.vupth.infrastructure.websocket;

import com.ospaulos.vupth.infrastructure.security.jwt.converter.JwtAuthenticationConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** BAK-5 (S14/B8): CONNECT com token invalido — anonimo (default) ou recusado (flag ligada). */
@ExtendWith(MockitoExtension.class)
@DisplayName("STOMP CONNECT — autenticacao pelo header Authorization")
class StompAuthChannelInterceptorTest {

    @Mock JwtDecoder jwtDecoder;
    @Mock JwtAuthenticationConverter converter;

    private final MessageChannel channel = mock(MessageChannel.class);

    private static Message<byte[]> frame(StompCommand command, String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (authorization != null) {
            accessor.addNativeHeader("Authorization", authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Object userOf(Message<?> message) {
        return MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class).getUser();
    }

    private StompAuthChannelInterceptor interceptor(boolean reject) {
        return new StompAuthChannelInterceptor(jwtDecoder, converter, reject);
    }

    @Test
    @DisplayName("token valido: principal com o userId e as roles")
    void validToken() {
        Jwt jwt = new Jwt("tok", Instant.now(), Instant.now().plusSeconds(60),
                java.util.Map.of("alg", "RS256"), java.util.Map.of("sub", "user-1"));
        when(jwtDecoder.decode("tok")).thenReturn(jwt);
        when(converter.convert(jwt)).thenReturn(new UsernamePasswordAuthenticationToken(
                "user-1", null, List.of(new SimpleGrantedAuthority("ROLE_CLIENT"))));

        Message<?> out = interceptor(true).preSend(frame(StompCommand.CONNECT, "Bearer tok"), channel);

        UsernamePasswordAuthenticationToken user = (UsernamePasswordAuthenticationToken) userOf(out);
        assertThat(user.getName()).isEqualTo("user-1");
        assertThat(user.getAuthorities()).extracting("authority").containsExactly("ROLE_CLIENT");
    }

    @Test
    @DisplayName("token valido sem conversao: principal sem roles")
    void validToken_nullConversion() {
        Jwt jwt = new Jwt("tok", Instant.now(), Instant.now().plusSeconds(60),
                java.util.Map.of("alg", "RS256"), java.util.Map.of("sub", "user-2"));
        when(jwtDecoder.decode("tok")).thenReturn(jwt);
        when(converter.convert(jwt)).thenReturn(null);

        Message<?> out = interceptor(false).preSend(frame(StompCommand.CONNECT, "Bearer tok"), channel);

        assertThat(((UsernamePasswordAuthenticationToken) userOf(out)).getAuthorities()).isEmpty();
    }

    @Test
    @DisplayName("sem token ou sem Bearer: anonimo, mesmo com a recusa ligada")
    void noToken_anonymous() {
        assertThat(userOf(interceptor(true).preSend(frame(StompCommand.CONNECT, null), channel))).isNull();
        assertThat(userOf(interceptor(true).preSend(frame(StompCommand.CONNECT, "Basic x"), channel))).isNull();
        verifyNoInteractions(jwtDecoder);
    }

    @Test
    @DisplayName("token invalido, flag desligada (default): segue anonimo, como antes")
    void invalidToken_default_anonymous() {
        when(jwtDecoder.decode("bad")).thenThrow(new JwtException("expired"));

        Message<?> out = interceptor(false).preSend(frame(StompCommand.CONNECT, "Bearer bad"), channel);

        assertThat(userOf(out)).isNull();
    }

    @Test
    @DisplayName("token invalido, flag ligada: CONNECT recusado com INVALID_TOKEN")
    void invalidToken_rejected() {
        when(jwtDecoder.decode("bad")).thenThrow(new JwtException("expired"));

        assertThatThrownBy(() -> interceptor(true).preSend(frame(StompCommand.CONNECT, "Bearer bad"), channel))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining(StompAuthChannelInterceptor.INVALID_TOKEN);
    }

    @Test
    @DisplayName("frames que nao sao CONNECT passam sem tocar no token")
    void otherFrames_untouched() {
        Message<byte[]> subscribe = frame(StompCommand.SUBSCRIBE, "Bearer bad");

        assertThat(interceptor(true).preSend(subscribe, channel)).isSameAs(subscribe);
        verifyNoInteractions(jwtDecoder);
    }
}
