package com.ospaulos.vupth.infrastructure.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** S16 (DEC-180): so o Cloudflare chega a API quando o cabecalho-segredo esta configurado. */
@DisplayName("cabecalho-segredo do Cloudflare")
class EdgeSecretFilterTest {

    private static MockHttpServletResponse run(EdgeSecretFilter filter, String path, String secret) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        if (secret != null) {
            req.addHeader("X-Origin-Secret", secret);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        return res;
    }

    @Test
    @DisplayName("desligado (vazio): tudo passa, como hoje")
    void disabled() throws Exception {
        EdgeSecretFilter f = new EdgeSecretFilter("", "X-Origin-Secret");
        assertThat(f.enabled()).isFalse();
        assertThat(run(f, "/establishment", null).getStatus()).isEqualTo(200);
        assertThat(new EdgeSecretFilter(null, "X-Origin-Secret").enabled()).isFalse();
    }

    @Test
    @DisplayName("ligado: sem o segredo ou com o errado, 403 EDGE_FORBIDDEN; com o certo, passa")
    void enabled() throws Exception {
        EdgeSecretFilter f = new EdgeSecretFilter("s3gr3d0", "X-Origin-Secret");
        assertThat(f.enabled()).isTrue();
        MockHttpServletResponse none = run(f, "/establishment", null);
        assertThat(none.getStatus()).isEqualTo(403);
        assertThat(none.getContentAsString()).contains("EDGE_FORBIDDEN");
        assertThat(run(f, "/establishment", "outro").getStatus()).isEqualTo(403);
        assertThat(run(f, "/establishment", "s3gr3d0").getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("ligado: o /actuator/health passa sem o segredo (healthcheck do Railway); o resto do actuator, nao")
    void healthIsOpen() throws Exception {
        EdgeSecretFilter f = new EdgeSecretFilter("s3gr3d0", "X-Origin-Secret");
        assertThat(run(f, "/actuator/health", null).getStatus()).isEqualTo(200);
        assertThat(run(f, "/actuator/health/readiness", null).getStatus()).isEqualTo(200);
        assertThat(run(f, "/actuator/healthz", null).getStatus()).isEqualTo(403);
        assertThat(run(f, "/actuator/info", null).getStatus()).isEqualTo(403);
    }
}
