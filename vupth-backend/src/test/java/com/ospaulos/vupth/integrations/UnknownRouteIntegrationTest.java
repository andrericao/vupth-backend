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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — rota inexistente responde <b>404</b>, não 500.
 *
 * <p><b>Regressão de 2026-08-12.</b> A partir do Spring 6.1 um request que não casa com nenhum
 * handler cai no servidor de arquivos estáticos e sai uma {@code NoResourceFoundException}. Como o
 * {@code GlobalExceptionHandler} não a tratava, ela chegava no {@code handleGeneric} e virava
 * <b>500 INTERNAL_ERROR</b> com stack trace no log.
 *
 * <p>Dois estragos, e o segundo foi o caro: o front não conseguia separar "rota não existe" de
 * "servidor quebrou"; e o log de produção enchia de {@code ERROR} com a <b>mesma assinatura</b> de
 * um 500 de verdade. Foi isso que escondeu, por semanas, que produção rodava uma build antiga —
 * cada rota nova chamada pelos apps virava um 500 indistinguível de defeito real.
 *
 * <p>⚠️ Ordem importa: o filtro de segurança roda <b>antes</b> do dispatcher. Numa rota inexistente
 * <b>sem</b> token a resposta é 401, não 404 — o request nem chega no handler. O 404 só aparece
 * para quem passou pela autenticação, ou sob um prefixo {@code permitAll}. Os dois casos estão
 * cobertos aqui porque os dois acontecem em produção.
 */
@Slf4j
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UnknownRouteIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private final Faker faker = new Faker();

    @Test
    @DisplayName("rota inexistente com token válido → 404 NOT_FOUND (não 500)")
    void unknownRoute_authenticated_returns404() throws Exception {
        String token = TestUserUtils.registerAndLoginUser(mvc, mapper, faker).token();

        mvc.perform(get("/rota-que-nao-existe/me").header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /**
     * O caso exato do log de produção: os apps chamavam rotas que existem no código mas não na
     * build no ar (ex.: {@code /swap-requests/me}, {@code /geocoding/reverse}), e recebiam 500.
     */
    @Test
    @DisplayName("rota de feature ausente na build → 404, não 500 (caso real de 12/08)")
    void unknownFeatureRoute_authenticated_returns404() throws Exception {
        String token = TestUserUtils.registerAndLoginUser(mvc, mapper, faker).token();

        mvc.perform(get("/feature-que-ainda-nao-subiu/me").header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("rota inexistente sob prefixo público → 404 mesmo sem token")
    void unknownRoute_underPublicPrefix_returns404WithoutToken() throws Exception {
        // GET /service-types/** é permitAll: passa a segurança e morre no dispatcher.
        mvc.perform(get("/service-types/nao/existe/isso/aqui"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("rota inexistente SEM token → 401 (a segurança responde antes do dispatcher)")
    void unknownRoute_anonymous_returns401() throws Exception {
        mvc.perform(get("/rota-que-nao-existe/me"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Guarda o handler vizinho: método errado numa rota que EXISTE continua 405. Sem essa
     * assertion, um handler de 404 largo demais engoliria o 405 e ninguém perceberia.
     */
    @Test
    @DisplayName("método errado em rota existente continua 405, não vira 404")
    void wrongMethodOnExistingRoute_stays405() throws Exception {
        String token = TestUserUtils.registerAndLoginUser(mvc, mapper, faker).token();

        mvc.perform(delete("/auth/forgot-password").header("Authorization", token))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}
