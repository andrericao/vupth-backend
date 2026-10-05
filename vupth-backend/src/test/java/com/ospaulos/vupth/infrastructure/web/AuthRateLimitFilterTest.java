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
    @DisplayName("conta por IP e por rota; sem ip-header configurado, o IP e o da conexao")
    void countsPerIpAndRoute() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 1, 60, Clock.fixed(T0, ZoneOffset.UTC));
        AtomicInteger passed = new AtomicInteger();

        run(filter, post("/auth/login", "2.2.2.2"), passed);
        run(filter, post("/auth/login", "3.3.3.3"), passed);
        run(filter, post("/auth/register", "2.2.2.2"), passed);
        MockHttpServletRequest forged = post("/auth/login", "2.2.2.2");
        forged.addHeader("X-Forwarded-For", "9.9.9.9");

        assertThat(run(filter, forged, passed).getStatus()).isEqualTo(429);
        assertThat(passed).hasValue(3);
    }

    @Test
    @DisplayName("ip-forjado-no-limite: com X-Forwarded-For, vale o ULTIMO valor — trocar o primeiro nao escapa")
    void forwardedForUsesLastHop() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 1, 60, Clock.fixed(T0, ZoneOffset.UTC), "",
                "X-Forwarded-For");
        AtomicInteger passed = new AtomicInteger();

        MockHttpServletRequest first = post("/auth/login", "10.0.0.1");
        first.addHeader("X-Forwarded-For", "1.1.1.1, 7.7.7.7");
        MockHttpServletRequest forged = post("/auth/login", "10.0.0.1");
        forged.addHeader("X-Forwarded-For", "8.8.8.8, 7.7.7.7");
        run(filter, first, passed);

        assertThat(run(filter, forged, passed).getStatus()).isEqualTo(429);
        assertThat(passed).hasValue(1);
    }

    @Test
    @DisplayName("ip-forjado-no-limite: com CF-Connecting-IP, o X-Forwarded-For forjado e ignorado")
    void cloudflareHeaderIgnoresForwardedFor() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 1, 60, Clock.fixed(T0, ZoneOffset.UTC), "",
                "CF-Connecting-IP");
        AtomicInteger passed = new AtomicInteger();

        MockHttpServletRequest first = post("/auth/register", "10.0.0.1");
        first.addHeader("CF-Connecting-IP", "7.7.7.7");
        first.addHeader("X-Forwarded-For", "1.1.1.1");
        MockHttpServletRequest forged = post("/auth/register", "10.0.0.1");
        forged.addHeader("CF-Connecting-IP", " 7.7.7.7 ");
        forged.addHeader("X-Forwarded-For", "2.2.2.2");
        MockHttpServletRequest other = post("/auth/register", "10.0.0.1");
        other.addHeader("CF-Connecting-IP", "6.6.6.6");
        run(filter, first, passed);

        assertThat(run(filter, forged, passed).getStatus()).isEqualTo(429);
        run(filter, other, passed);
        assertThat(passed).hasValue(2);
    }

    @Test
    @DisplayName("ip-header configurado mas ausente no pedido: cai no IP da conexao")
    void missingHeaderFallsBackToRemoteAddr() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 1, 60, Clock.fixed(T0, ZoneOffset.UTC), "",
                "CF-Connecting-IP");
        AtomicInteger passed = new AtomicInteger();

        run(filter, post("/auth/login", "4.4.4.4"), passed);
        MockHttpServletRequest blank = post("/auth/login", "4.4.4.4");
        blank.addHeader("CF-Connecting-IP", " ");

        assertThat(run(filter, blank, passed).getStatus()).isEqualTo(429);
        assertThat(passed).hasValue(1);
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

    @Test
    @DisplayName("o CI com o header X-Vupth-CI igual ao segredo passa por fora do limite")
    void ciHeaderWithSecretSkipsLimit() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 1, 60, Clock.fixed(T0, ZoneOffset.UTC), "s3gredo");
        AtomicInteger passed = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest request = post("/auth/register", "4.4.4.4");
            request.addHeader(AuthRateLimitFilter.CI_HEADER, "s3gredo");
            assertThat(run(filter, request, passed).getStatus()).isEqualTo(200);
        }

        assertThat(passed).hasValue(5);
    }

    @Test
    @DisplayName("header do CI com valor errado conta como qualquer outro pedido")
    void ciHeaderWithWrongValueIsLimited() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 1, 60, Clock.fixed(T0, ZoneOffset.UTC), "s3gredo");
        AtomicInteger passed = new AtomicInteger();

        MockHttpServletRequest first = post("/auth/login", "5.5.5.5");
        first.addHeader(AuthRateLimitFilter.CI_HEADER, "chute");
        MockHttpServletRequest second = post("/auth/login", "5.5.5.5");
        second.addHeader(AuthRateLimitFilter.CI_HEADER, "chute");
        run(filter, first, passed);

        assertThat(run(filter, second, passed).getStatus()).isEqualTo(429);
        assertThat(passed).hasValue(1);
    }

    @Test
    @DisplayName("sem segredo configurado, nem header vazio passa por fora")
    void noSecretConfiguredNeverSkips() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 1, 60, Clock.fixed(T0, ZoneOffset.UTC), "");
        AtomicInteger passed = new AtomicInteger();

        MockHttpServletRequest first = post("/auth/login", "6.6.6.6");
        first.addHeader(AuthRateLimitFilter.CI_HEADER, "");
        MockHttpServletRequest second = post("/auth/login", "6.6.6.6");
        second.addHeader(AuthRateLimitFilter.CI_HEADER, "");
        run(filter, first, passed);

        assertThat(run(filter, second, passed).getStatus()).isEqualTo(429);
        assertThat(passed).hasValue(1);
    }
}
