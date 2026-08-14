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
 * enquanto o teste de MockMvc dava o caso como verde. A leitura imediata foi que o MockMvc estava
 * mentindo e que a build nova havia mudado o comportamento.
 *
 * <p><b>Estes testes desfizeram essa leitura.</b> Em container real, com o mesmo codigo, a resposta
 * e <b>401</b> — igual ao MockMvc. Nao ha divergencia entre teste e producao, e a ordem que o
 * commit do 404 afirmava preservar esta correta.
 *
 * <p><b>Entao de onde veio o 404?</b> Nao da aplicacao. O edge do Render responde
 * {@code 404 Not Found} em <b>texto puro</b> quando a instancia esta indisponivel, e o run que
 * falhou tem a impressao digital disso: um request de <b>2m16s</b> (cold start) no meio, e o
 * request IMEDIATAMENTE ANTERIOR — que exige corpo JSON com {@code code} — passando. A aplicacao
 * estava viva num, e sumiu no seguinte.
 *
 * <p><b>A licao, que nao e sobre 404:</b> um teste de contrato so vale se souber distinguir "a
 * aplicacao respondeu errado" de "a aplicacao nao respondeu". Sem isso, uma queda de instancia
 * chega disfarcada de defeito de codigo — e custa uma investigacao inteira, como custou aqui. A
 * guarda que separa os dois casos ficou na collection do Postman, onde o problema aparece.
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
     * <p>Como o codigo faz o certo nos dois lugares, o 404 que apareceu em homolog nao veio da
     * aplicacao: veio do edge do Render, que responde {@code 404 Not Found} em texto puro quando
     * a instancia esta indisponivel. O corpo e o que separa os dois — a aplicacao responde JSON
     * com {@code code}, o edge responde texto. A guarda que torna isso legivel esta na collection.
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
