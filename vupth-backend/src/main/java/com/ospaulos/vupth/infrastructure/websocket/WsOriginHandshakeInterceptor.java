package com.ospaulos.vupth.infrastructure.websocket;

import com.ospaulos.vupth.infrastructure.security.webcookie.WebCookieAuth;
import com.ospaulos.vupth.infrastructure.web.AllowedOrigins;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

/**
 * Origem do handshake do {@code /ws} (api-no-dominio, DEC-180 · S22). Hoje o {@code /ws} aceita qualquer origem; no modo cookie isso
 * seria sequestro de WebSocket entre sites (outro site abre o socket e o navegador manda o cookie). Regras:
 *
 * <ul>
 *   <li><b>Sem {@code Origin}</b> ou com a {@code Origin} do proprio host da API (o app nativo): passa, como sempre.</li>
 *   <li><b>Origin da lista do CORS</b> ({@code CORS_ALLOWED_ORIGINS}): passa e, com o token da web em cookie ligado, o
 *       {@value WebCookieAuth#ACCESS} vai para a sessao do socket; o CONNECT o usa quando nao vem o {@code Authorization} (o canal
 *       pessoal {@code /user/...} funciona no modo cookie).</li>
 *   <li><b>Outra origem:</b> com {@code vupth.ws.check-origin} ligado, 403; desligado (o padrao, e o staging), passa anonimo, sem o
 *       cookie, como hoje.</li>
 * </ul>
 * O cookie so e lido de origem da lista: o sequestro fica fechado mesmo com a chave desligada.
 */
@Slf4j
@Component
public class WsOriginHandshakeInterceptor implements HandshakeInterceptor {

    /** Atributo da sessao STOMP com o token lido do cookie no handshake. */
    public static final String COOKIE_TOKEN = "vupth.ws.cookie-token";

    private final AllowedOrigins origins;
    private final WebCookieAuth webCookie;
    private final boolean checkOrigin;

    public WsOriginHandshakeInterceptor(AllowedOrigins origins, WebCookieAuth webCookie,
                                        @Value("${vupth.ws.check-origin:false}") boolean checkOrigin) {
        this.origins = origins;
        this.webCookie = webCookie;
        this.checkOrigin = checkOrigin;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String origin = request.getHeaders().getOrigin();
        if (origin == null || origin.isBlank() || sameHost(origin, request.getHeaders().getFirst("Host"))) {
            return true;
        }
        if (origins.allows(origin)) {
            if (webCookie.enabled() && request instanceof ServletServerHttpRequest servlet) {
                HttpServletRequest http = servlet.getServletRequest();
                WebCookieAuth.read(http, WebCookieAuth.ACCESS).ifPresent(token -> attributes.put(COOKIE_TOKEN, token));
            }
            return true;
        }
        if (checkOrigin) {
            log.warn("[WS] handshake RECUSADO: origem fora da lista ({})", origin);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {
        // nada
    }

    /** A Origin aponta para o proprio host da API (o React Native manda a do servidor). Compara so o host e a porta, sem o esquema. */
    static boolean sameHost(String origin, String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(origin.trim());
            if (uri.getHost() == null) {
                return false;
            }
            String host = uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
            String expected = hostHeader.trim();
            return host.equalsIgnoreCase(expected)
                    || (uri.getPort() == -1 && expected.toLowerCase().startsWith(uri.getHost().toLowerCase() + ":"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
