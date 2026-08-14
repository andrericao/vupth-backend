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
 * 2026-08-13 o Newman de homolog reprovou "rota inexistente sem token → 401" recebendo <b>404</b>,
 * enquanto o teste de MockMvc dava o caso como verde. Seguiram-se quatro hipoteses erradas (MockMvc
 * mentindo, mudanca de comportamento da build nova, deploys concorrentes, OOM da instancia), todas
 * derrubadas por evidencia.
 *
 * <p><b>A causa real era o proprio request do Postman:</b> a collection tem {@code auth} bearer na
 * raiz, e o request declarava {@code noauth} no nivel do <b>item</b> em vez de dentro de
 * {@code request} — posicao que o Newman ignora. O request chamado "sem token" ia <b>com</b> o
 * token do owner, e a aplicacao respondia o 404 correto de rota inexistente autenticada.
 *
 * <p><b>O que estes testes garantem, e por isso ficam:</b> o contrato dos cinco casos, exercitado
 * por HTTP real contra o Tomcat — inclusive o par que impede o <b>oraculo de enumeracao</b> (rota
 * que existe e rota que nao existe respondem o MESMO 401 a quem nao tem credencial). Sem eles, a
 * unica prova do contrato seria uma collection cuja autenticacao ja se provou facil de errar em
 * silencio.
 *
 * <p>⚠️ <b>A licao:</b> quando um teste de ambiente diverge de um teste local, a primeira pergunta
 * e "o request e mesmo o que o nome diz?", nao "o que ha de diferente no ambiente?". A evidencia
 * decisiva — o corpo JSON da resposta, que so a aplicacao produz — estava disponivel desde o
 * primeiro minuto e atravessou quatro hipoteses sem ser olhada.
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
     * ⚠️ O caso que reprovou o Newman de homolog em 13/08 — e que aqui, em container real,
     * responde <b>401</b>. Foi esta assercao que desfez a hipotese de que a build nova tinha
     * mudado o comportamento: <b>nao mudou</b>. A ordem que o commit do 404 afirmava preservar
     * ("sem token continua 401, porque a seguranca responde antes do dispatcher") esta correta,
     * e vale tambem fora do MockMvc.
     *
     * <p>O 404 do Newman vinha de o request estar indo <b>autenticado</b> sem que o nome dissesse
     * (ver o javadoc da classe): para quem tem token, rota inexistente e 404 mesmo — o que este
     * arquivo prova no teste de cima. Os dois lado a lado sao a diferenca entre "com token" e
     * "sem token", que e exatamente o que a collection errou.
     */
    @Test
    @DisplayName("rota inexistente SEM token → 401 (a seguranca responde antes do dispatcher)")
    void unknownRoute_anonymous_returns401() {
        ResponseEntity<String> response = call(HttpMethod.GET, "/rota-que-nao-existe/me", null);

        log.info("[probe] anonimo em rota inexistente -> {} | body={}",
                response.getStatusCode(), response.getBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * O par do teste acima. Rota que EXISTE e e protegida responde <b>o mesmo 401</b> de uma rota
     * que nao existe — entao o status <b>nao revela</b> a existencia da rota a quem esta sem
     * credencial. Se um dia a de cima passar a responder 404 enquanto esta segue 401, a API vira
     * um oraculo de enumeracao; e por isso que as duas ficam lado a lado.
     */
    @Test
    @DisplayName("rota EXISTENTE protegida SEM token → 401, o MESMO da inexistente (sem oraculo de enumeracao)")
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
