package com.ospaulos.vupth.infrastructure.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class SlowRequestLogFilterTest {

    @Test
    @DisplayName("request acima do limite: WARN com metodo, rota, status e tempo")
    void slowRequestIsLogged(CapturedOutput output) throws Exception {
        SlowRequestLogFilter filter = new SlowRequestLogFilter(0);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(new MockHttpServletRequest("GET", "/professional/me"), response, (req, res) -> { });

        assertThat(output).contains("[SLOW] GET /professional/me -> 200 em");
    }

    @Test
    @DisplayName("request rapida e probes do actuator: nada em WARN")
    void fastAndActuatorAreQuiet(CapturedOutput output) throws Exception {
        new SlowRequestLogFilter(60_000).doFilter(
                new MockHttpServletRequest("GET", "/professional/me"), new MockHttpServletResponse(), (req, res) -> { });
        new SlowRequestLogFilter(0).doFilter(
                new MockHttpServletRequest("GET", "/actuator/health/readiness"), new MockHttpServletResponse(), (req, res) -> { });

        assertThat(output).doesNotContain("[SLOW]");
    }
}
