package com.ospaulos.vupth.infrastructure.websocket;

import com.ospaulos.vupth.infrastructure.security.webcookie.WebCookieAuth;
import com.ospaulos.vupth.infrastructure.web.AllowedOrigins;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** S22 (DEC-180): a origem do handshake do /ws contra a lista do CORS, e o cookie so de origem da lista. */
@DisplayName("/ws — origem do handshake e cookie")
class WsOriginHandshakeInterceptorTest {

    private static final AllowedOrigins ORIGINS = new AllowedOrigins("https://app.vupth.com,https://*.vupth-partner.pages.dev");

    private static WebCookieAuth cookie(boolean enabled) {
        return new WebCookieAuth(enabled, "Lax", "", "", 900);
    }

    private record Result(boolean passed, Map<String, Object> attributes, int status) {}

    private static Result handshake(WsOriginHandshakeInterceptor interceptor, String origin, String host, boolean withCookie) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ws");
        if (origin != null) {
            req.addHeader("Origin", origin);
        }
        if (host != null) {
            req.addHeader("Host", host);
        }
        if (withCookie) {
            req.setCookies(new Cookie(WebCookieAuth.ACCESS, "tok"));
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        Map<String, Object> attributes = new HashMap<>();
        boolean passed = interceptor.beforeHandshake(new ServletServerHttpRequest(req), new ServletServerHttpResponse(res),
                mock(org.springframework.web.socket.WebSocketHandler.class), attributes);
        return new Result(passed, attributes, res.getStatus());
    }

    @Test
    @DisplayName("sem Origin (app nativo): passa, sem cookie")
    void noOrigin() {
        Result r = handshake(new WsOriginHandshakeInterceptor(ORIGINS, cookie(true), true), null, "api.vupth.com", true);
        assertThat(r.passed()).isTrue();
        assertThat(r.attributes()).isEmpty();
    }

    @Test
    @DisplayName("Origin do proprio host (React Native): passa mesmo com a checagem ligada")
    void sameHost() {
        Result r = handshake(new WsOriginHandshakeInterceptor(ORIGINS, cookie(true), true),
                "https://api.vupth.com", "api.vupth.com", true);
        assertThat(r.passed()).isTrue();
        assertThat(r.attributes()).isEmpty();
    }

    @Test
    @DisplayName("Origin da lista com o modo cookie ligado: passa e leva o token do cookie para a sessao")
    void allowedOriginCopiesCookie() {
        Result r = handshake(new WsOriginHandshakeInterceptor(ORIGINS, cookie(true), true),
                "https://app.vupth.com", "api.vupth.com", true);
        assertThat(r.passed()).isTrue();
        assertThat(r.attributes()).containsEntry(WsOriginHandshakeInterceptor.COOKIE_TOKEN, "tok");
    }

    @Test
    @DisplayName("Origin da lista com o modo cookie desligado: passa, sem o cookie")
    void allowedOriginCookieOff() {
        Result r = handshake(new WsOriginHandshakeInterceptor(ORIGINS, cookie(false), true),
                "https://abc.vupth-partner.pages.dev", "api.vupth.com", true);
        assertThat(r.passed()).isTrue();
        assertThat(r.attributes()).isEmpty();
    }

    @Test
    @DisplayName("origem de fora com a checagem ligada: 403")
    void foreignOriginRejected() {
        Result r = handshake(new WsOriginHandshakeInterceptor(ORIGINS, cookie(true), true),
                "https://outro.site", "api.vupth.com", true);
        assertThat(r.passed()).isFalse();
        assertThat(r.status()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    @DisplayName("origem de fora com a checagem desligada: passa anonima, NUNCA com o cookie")
    void foreignOriginNoCookie() {
        Result r = handshake(new WsOriginHandshakeInterceptor(ORIGINS, cookie(true), false),
                "https://outro.site", "api.vupth.com", true);
        assertThat(r.passed()).isTrue();
        assertThat(r.attributes()).isEmpty();
    }

    @Test
    @DisplayName("sameHost: porta, Host ausente e Origin invalida")
    void sameHostEdges() {
        assertThat(WsOriginHandshakeInterceptor.sameHost("http://localhost:8080", "localhost:8080")).isTrue();
        assertThat(WsOriginHandshakeInterceptor.sameHost("https://api.vupth.com", "api.vupth.com:443")).isTrue();
        assertThat(WsOriginHandshakeInterceptor.sameHost("https://api.vupth.com", null)).isFalse();
        assertThat(WsOriginHandshakeInterceptor.sameHost("https://api.vupth.com", " ")).isFalse();
        assertThat(WsOriginHandshakeInterceptor.sameHost("null", "api.vupth.com")).isFalse();
        assertThat(WsOriginHandshakeInterceptor.sameHost("ht tp://x", "api.vupth.com")).isFalse();
        assertThat(WsOriginHandshakeInterceptor.sameHost("https://evil.com", "api.vupth.com")).isFalse();
    }
}
