package com.ospaulos.vupth.integrations.security;

import com.ospaulos.vupth.VupthApplication;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * readiness-401 (11/10): o UptimeRobot viu <b>401</b> no {@code /actuator/health/readiness} da producao
 * durante o deploy, numa rota publica. Um erro na rota (o {@code sendError} do container) leva ao
 * despacho de ERRO para o {@code /error}; com o {@code /error} fechado, a seguranca respondia 401 e
 * escondia o status de verdade.
 *
 * <p>Por HTTP de verdade, contra o Tomcat: o MockMvc nao faz o despacho de erro. Um filtro de teste,
 * depois da seguranca, simula o erro so quando o pedido traz {@link #SIMULA}; o resto do contexto
 * segue igual.
 */
// Os probes so ligam em staging/prod; aqui ligados como la, para o readiness existir.
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.endpoint.health.probes.enabled=true")
@ActiveProfiles("test")
@Import(ErrorDispatchRealServerIntegrationTest.FalhaSimulada.class)
class ErrorDispatchRealServerIntegrationTest {

    static final String SIMULA = "X-Test-Simula-Erro";

    @Autowired TestRestTemplate rest;

    @TestConfiguration
    static class FalhaSimulada {
        @Bean
        FilterRegistrationBean<OncePerRequestFilter> falhaSimulada() {
            OncePerRequestFilter filter = new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                FilterChain chain) throws ServletException, IOException {
                    String status = request.getHeader(SIMULA);
                    if (status != null) {
                        response.sendError(Integer.parseInt(status));
                        return;
                    }
                    chain.doFilter(request, response);
                }
            };
            FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>(filter);
            bean.setOrder(Ordered.LOWEST_PRECEDENCE);
            return bean;
        }
    }

    private ResponseEntity<String> get(String path, String simula) {
        HttpHeaders headers = new HttpHeaders();
        if (simula != null) {
            headers.set(SIMULA, simula);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("readiness com erro, sem token → o 503 de verdade, nao 401")
    void readinessError_anonymous_keepsRealStatus() {
        ResponseEntity<String> response = get("/actuator/health/readiness", "503");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("erro 500 numa rota publica, sem token → 500, sem stack no corpo")
    void publicRouteError_anonymous_keeps500() {
        ResponseEntity<String> response = get("/actuator/health/readiness", "500");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).doesNotContain("Exception", "at com.");
    }

    @Test
    @DisplayName("readiness sem erro continua 200 publico")
    void readiness_anonymous_200() {
        ResponseEntity<String> response = get("/actuator/health/readiness", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("o /error liberado nao abre rota protegida: sem token continua 401")
    void protectedRoute_anonymous_still401() {
        ResponseEntity<String> response = get("/attendances/client/me", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("o /error chamado direto, sem token, nao vira porta: 401")
    void errorPath_directRequest_anonymous_401() {
        ResponseEntity<String> response = get("/error", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
