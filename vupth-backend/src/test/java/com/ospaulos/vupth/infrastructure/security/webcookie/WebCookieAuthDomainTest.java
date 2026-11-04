package com.ospaulos.vupth.infrastructure.security.webcookie;

import com.ospaulos.vupth.api.user.dto.response.auth.RefreshTokenResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** api-no-dominio (DEC-180): os HttpOnly ficam host-only na API; o de CSRF vai em .vupth.com para a pagina ler. */
@DisplayName("cookie da web — dominio de cada cookie")
class WebCookieAuthDomainTest {

    private static List<String> written(WebCookieAuth auth) {
        MockHttpServletResponse res = new MockHttpServletResponse();
        auth.write(res, new RefreshTokenResponse("at", "rt", 3600L));
        return res.getHeaders("Set-Cookie");
    }

    private static String named(List<String> headers, String name) {
        return headers.stream().filter(h -> h.startsWith(name + "=")).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("csrf-domain .vupth.com com o dominio vazio: vupth_at e vupth_rt host-only, vupth_csrf em .vupth.com, Lax sem Partitioned")
    void csrfOnParentDomain() {
        List<String> h = written(new WebCookieAuth(true, "Lax", "", ".vupth.com", 900));
        assertThat(named(h, WebCookieAuth.ACCESS)).doesNotContain("Domain=").contains("HttpOnly", "SameSite=Lax")
                .doesNotContain("Partitioned");
        assertThat(named(h, WebCookieAuth.REFRESH)).doesNotContain("Domain=");
        assertThat(named(h, WebCookieAuth.CSRF)).contains("Domain=.vupth.com").doesNotContain("HttpOnly");

        MockHttpServletResponse out = new MockHttpServletResponse();
        new WebCookieAuth(true, "Lax", "", ".vupth.com", 900).clear(out);
        assertThat(named(out.getHeaders("Set-Cookie"), WebCookieAuth.CSRF)).contains("Domain=.vupth.com", "Max-Age=0");
    }

    @Test
    @DisplayName("csrf-domain vazio: o de CSRF segue o dominio dos outros (como antes)")
    void csrfFollowsDomain() {
        List<String> h = written(new WebCookieAuth(true, "None", "api.exemplo.com", " ", 900));
        assertThat(named(h, WebCookieAuth.ACCESS)).contains("Domain=api.exemplo.com", "Partitioned");
        assertThat(named(h, WebCookieAuth.CSRF)).contains("Domain=api.exemplo.com");
    }
}
