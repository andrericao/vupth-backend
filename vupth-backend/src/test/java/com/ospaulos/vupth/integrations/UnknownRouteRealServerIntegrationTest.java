package com.ospaulos.vupth.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests — rota inexistente, exercitada por <b>HTTP de verdade</b> contra o Tomcat.
 *
 * <p><b>Por que este arquivo existe, separado do {@link UnknownRouteIntegrationTest}.</b> Em
 * 2026-08-13 o Newman de homolog reprovou uma assercao que o teste de MockMvc dava como verde:
 * rota inexistente <b>sem token</b> respondia <b>401</b> no MockMvc e <b>404</b> no servidor real.
 * Nao era o servidor que estava errado — era o teste que nao exercitava o caminho de producao.
 *
 * <p><b>A diferenca:</b> quando a seguranca nega um anonimo, o entry point chama
 * {@code response.sendError(401)}. O MockMvc <b>para ai</b> e devolve 401. Um container real
 * <b>re-despacha</b> o request (DispatcherType.ERROR) para {@code /error}, e e nesse segundo
 * trajeto que o handler de {@code NoResourceFoundException} pode entrar e trocar o status. Todo
 * teste que afirma algo sobre o status FINAL de um caminho que passa por {@code sendError} precisa
 * de container real para valer.
 *
 * <p>E a mesma licao do teste de fuso (BUG2b): teste que nao roda o caminho de producao fica verde
 * com o defeito presente — e o alarme so toca quando ninguem esta ouvindo.
 *
 * <p>⚠️ <b>A decisao de contrato que estes testes tornam visivel:</b> se rota inexistente responde
 * 404 ao anonimo enquanto rota EXISTENTE protegida responde 401, a API vira um <b>oraculo de
 * enumeracao</b> — de fora, sem credencial nenhuma, da para descobrir quais rotas existem. Os dois
 * casos estao aqui lado a lado de proposito, para a escolha ser feita com o fato na mesa.
 */
@Slf4j
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UnknownRouteRealServerIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    /** Cliente HTTP de verdade — o {@code port} ja vem embutido nele com RANDOM_PORT. */
    @Autowired TestRestTemplate rest;

    private final Faker faker = new Faker();

    private ResponseEntity<String> call(HttpMethod method, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set(HttpHeaders.AUTHORIZATION, token);
        }
        return rest.exchange(path, method, new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("rota inexistente COM token → 404 NOT_FOUND (o caso real dos apps em 12/08)")
    void unknownRoute_authenticated_returns404() throws Exception {
        String token = TestUserUtils.registerAndLoginUser(mvc, mapper, faker).token();

        ResponseEntity<String> response = call(HttpMethod.GET, "/rota-que-nao-existe/me", token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("NOT_FOUND");
    }

    /**
     * ⚠️ O caso que reprovou em homolog. O MockMvc afirma 401 aqui; o servidor real respondeu 404.
     * Esta assercao registra o que PRODUCAO faz — se ela quebrar, foi o comportamento que mudou,
     * e nao o teste que estava errado.
     */
    @Test
    @DisplayName("rota inexistente SEM token → o que o servidor REAL responde (401 no MockMvc, 404 em homolog)")
    void unknownRoute_anonymous_realServerBehaviour() {
        ResponseEntity<String> response = call(HttpMethod.GET, "/rota-que-nao-existe/me", null);

        log.info("[probe] anonimo em rota inexistente -> {} | body={}",
                response.getStatusCode(), response.getBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * O outro lado do oraculo: rota que EXISTE e e protegida. Se esta responde 401 e a de cima
     * responde 404, a diferenca entre os dois status revela a existencia da rota a quem nao tem
     * credencial nenhuma.
     */
    @Test
    @DisplayName("rota EXISTENTE protegida SEM token → 401 (o par que revela o oraculo de enumeracao)")
    void existingProtectedRoute_anonymous_returns401() {
        ResponseEntity<String> response = call(HttpMethod.GET, "/attendances/client/me", null);

        log.info("[probe] anonimo em rota existente protegida -> {}", response.getStatusCode());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("rota inexistente sob prefixo publico → 404 mesmo sem token")
    void unknownRoute_underPublicPrefix_returns404WithoutToken() {
        ResponseEntity<String> response = call(HttpMethod.GET, "/service-types/nao/existe/isso", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("NOT_FOUND");
    }

    @Test
    @DisplayName("metodo errado em rota existente continua 405, nao vira 404")
    void wrongMethodOnExistingRoute_stays405() throws Exception {
        String token = TestUserUtils.registerAndLoginUser(mvc, mapper, faker).token();

        ResponseEntity<String> response = call(HttpMethod.DELETE, "/auth/forgot-password", token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).contains("METHOD_NOT_ALLOWED");
    }
}
