package com.ospaulos.vupth.infrastructure.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRateLimitFilterTest {

    private static final Instant T0 = Instant.parse("2026-10-03T12:00:00Z");

    private static MockHttpServletRequest post(String uri, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr(ip);
        return request;
    }

    /** Roda o filtro e diz se a request passou para a cadeia. */
    private static MockHttpServletResponse run(AuthRateLimitFilter filter, MockHttpServletRequest request,
                                               AtomicInteger passed) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> passed.incrementAndGet());
        return response;
    }

    @Test
    @DisplayName("acima do teto na janela: 429 TOO_MANY_REQUESTS com Retry-After, sem chegar ao controller")
    void overLimitIs429() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 2, 60, Clock.fixed(T0, ZoneOffset.UTC));
        AtomicInteger passed = new AtomicInteger();

        run(filter, post("/auth/login", "1.1.1.1"), passed);
        run(filter, post("/auth/login", "1.1.1.1"), passed);
        MockHttpServletResponse third = run(filter, post("/auth/login", "1.1.1.1"), passed);

        assertThat(passed).hasValue(2);
        assertThat(third.getStatus()).isEqualTo(429);
        assertThat(third.getHeader("Retry-After")).isEqualTo("60");
        assertThat(third.getContentAsString()).contains("\"code\":\"TOO_MANY_REQUESTS\"");
    }

    @Test
    @DisplayName("a janela vira: depois de window-seconds o IP volta a passar")
    void windowResets() throws Exception {
        Clock[] clock = { Clock.fixed(T0, ZoneOffset.UTC) };
        AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 1, 60, new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return clock[0].instant(); }
        });
        AtomicInteger passed = new AtomicInteger();

        run(filter, post("/auth/forgot-password", "1.1.1.1"), passed);
        assertThat(run(filter, post("/auth/forgot-password", "1.1.1.1"), passed).getStatus()).isEqualTo(429);

        clock[0] = Clock.fixed(T0.plusSeconds(60), ZoneOffset.UTC);
        run(filter, post("/auth/forgot-password", "1.1.1.1"), passed);

        assertThat(passed).hasValue(2);
    }

    @Test
    @DisplayName("conta por IP (primeiro do X-Forwarded-For) e por rota")
    void countsPerIpAndRoute() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 1, 60, Clock.fixed(T0, ZoneOffset.UTC));
        AtomicInteger passed = new AtomicInteger();

        MockHttpServletRequest viaProxyA = post("/auth/login", "10.0.0.1");
        viaProxyA.addHeader("X-Forwarded-For", "2.2.2.2, 10.0.0.1");
        MockHttpServletRequest viaProxyB = post("/auth/login", "10.0.0.1");
        viaProxyB.addHeader("X-Forwarded-For", "3.3.3.3");

        run(filter, viaProxyA, passed);
        run(filter, viaProxyB, passed);
        run(filter, post("/auth/register", "2.2.2.2"), passed);

        assertThat(passed).hasValue(3);
    }

    @Test
    @DisplayName("fora do escopo: desligado, GET ou outra rota nunca sao barrados")
    void outOfScopeNeverLimited() throws Exception {
        AuthRateLimitFilter off = new AuthRateLimitFilter(false, 0, 60, Clock.fixed(T0, ZoneOffset.UTC));
        AuthRateLimitFilter on = new AuthRateLimitFilter(true, 0, 60, Clock.fixed(T0, ZoneOffset.UTC));
        AtomicInteger passed = new AtomicInteger();

        run(off, post("/auth/login", "1.1.1.1"), passed);
        run(on, new MockHttpServletRequest("GET", "/auth/login"), passed);
        run(on, post("/auth/refresh", "1.1.1.1"), passed);

        assertThat(passed).hasValue(3);
    }
}
