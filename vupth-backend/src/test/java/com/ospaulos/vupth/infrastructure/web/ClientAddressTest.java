package com.ospaulos.vupth.infrastructure.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests — o IP e a porta de quem chamou, atras do proxy (ip-forjado-no-limite, registro-de-acesso). */
class ClientAddressTest {

    private static MockHttpServletRequest request() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("10.0.0.9");
        r.setRemotePort(41234);
        return r;
    }

    @Test
    @DisplayName("Sem header configurado ou presente: IP e porta da conexao")
    void direct() {
        MockHttpServletRequest r = request();
        assertThat(ClientAddress.ip(r, "")).isEqualTo("10.0.0.9");
        assertThat(ClientAddress.ip(r, null)).isEqualTo("10.0.0.9");
        assertThat(ClientAddress.ip(r, "CF-Connecting-IP")).isEqualTo("10.0.0.9");
        assertThat(ClientAddress.port(r, "CF-Connecting-IP", "X-Port")).isEqualTo(41234);
    }

    @Test
    @DisplayName("X-Forwarded-For vale pelo ultimo valor (o da borda); outro header, pelo valor inteiro")
    void headers() {
        MockHttpServletRequest r = request();
        r.addHeader("X-Forwarded-For", "6.6.6.6, 200.1.2.3");
        r.addHeader("CF-Connecting-IP", " 177.10.0.1 ");
        assertThat(ClientAddress.ip(r, "X-Forwarded-For")).isEqualTo("200.1.2.3");
        assertThat(ClientAddress.ip(r, "CF-Connecting-IP")).isEqualTo("177.10.0.1");
    }

    @Test
    @DisplayName("Atras do proxy: a porta so vem do header da plataforma; sem ele, ou invalida, null")
    void portBehindProxy() {
        MockHttpServletRequest r = request();
        r.addHeader("CF-Connecting-IP", "177.10.0.1");
        assertThat(ClientAddress.port(r, "CF-Connecting-IP", "")).isNull();
        assertThat(ClientAddress.port(r, "CF-Connecting-IP", "X-Port")).isNull();
        r.addHeader("X-Port", "51000");
        assertThat(ClientAddress.port(r, "CF-Connecting-IP", "X-Port")).isEqualTo(51000);

        MockHttpServletRequest bad = request();
        bad.addHeader("CF-Connecting-IP", "177.10.0.1");
        bad.addHeader("X-Port", "abc");
        assertThat(ClientAddress.port(bad, "CF-Connecting-IP", "X-Port")).isNull();
        MockHttpServletRequest out = request();
        out.addHeader("CF-Connecting-IP", "177.10.0.1");
        out.addHeader("X-Port", "70000");
        assertThat(ClientAddress.port(out, "CF-Connecting-IP", "X-Port")).isNull();
        MockHttpServletRequest zero = request();
        zero.addHeader("CF-Connecting-IP", "177.10.0.1");
        zero.addHeader("X-Port", "0");
        assertThat(ClientAddress.port(zero, "CF-Connecting-IP", "X-Port")).isNull();
    }

    @Test
    @DisplayName("AccessOrigin usa os headers configurados")
    void accessOrigin() {
        MockHttpServletRequest r = request();
        r.addHeader("CF-Connecting-IP", "177.10.0.1");
        r.addHeader("X-Port", "51000");
        AccessOrigin origin = new AccessOrigin("CF-Connecting-IP", "X-Port");
        assertThat(origin.ip(r)).isEqualTo("177.10.0.1");
        assertThat(origin.port(r)).isEqualTo(51000);
    }
}
