package com.ospaulos.vupth.integrations.config;

import com.ospaulos.vupth.VupthApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** estudo-desempenho (DEC-145, proposta 3): a leitura publica estavel manda ETag e responde 304, sem corpo, ao repetir. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HttpCacheIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    @DisplayName("GET /payment-gateway: 200 com ETag e corpo; o mesmo If-None-Match volta 304 sem corpo; outro valor volta 200")
    void etagOnPublicRead() throws Exception {
        var first = mvc.perform(get("/payment-gateway")).andExpect(status().isOk()).andReturn().getResponse();
        String etag = first.getHeader(HttpHeaders.ETAG);
        assertThat(etag).as("ETag presente").isNotBlank();
        assertThat(first.getContentAsByteArray().length).as("antes: o corpo vai inteiro").isPositive();

        var second = mvc.perform(get("/payment-gateway").header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified()).andReturn().getResponse();
        assertThat(second.getContentAsByteArray().length).as("depois: 304 sem corpo").isZero();

        mvc.perform(get("/payment-gateway").header(HttpHeaders.IF_NONE_MATCH, "\"outro\"")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("rota fora da lista (fila/pagamento/usuario) nao ganha ETag")
    void otherRoutesHaveNoEtag() throws Exception {
        var response = mvc.perform(get("/actuator/health")).andReturn().getResponse();
        assertThat(response.getHeader(HttpHeaders.ETAG)).isNull();
    }
}
