package com.ospaulos.vupth.integrations.security;

import com.ospaulos.vupth.VupthApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — documentacao da API (BAK-2 springdoc): aberta em dev/staging, fechada na producao.
 *
 * <p>A licao do {@code CorsAndActuatorIntegrationTest}: no cenario "producao" o springdoc continua
 * <b>ligado</b>. Se ele estivesse desligado, o assert de "negado" passaria pelo motivo errado (404 de
 * endpoint ausente) — inclusive com a regra de seguranca apagada. Ligado e mesmo assim recusado prova
 * que quem barra e a cadeia.</p>
 */
class ApiDocsIntegrationTest {

    @Nested
    @SpringBootTest(classes = VupthApplication.class)
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
            "springdoc.api-docs.enabled=true",
            "springdoc.swagger-ui.enabled=true",
            "vupth.api-docs.public=true"
    })
    @DisplayName("dev/staging (vupth.api-docs.public=true): documentacao aberta, sem token")
    class Aberta {

        @Autowired
        MockMvc mvc;

        @Test
        @DisplayName("/v3/api-docs responde 200 com o OpenAPI 3 e as rotas da API")
        void apiDocs_aberto() throws Exception {
            mvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.openapi").value(startsWith("3.")))
                    .andExpect(jsonPath("$.paths['/auth/login']").exists());
        }

        @Test
        @DisplayName("/v3/api-docs declara o esquema Bearer JWT (botao Authorize do Swagger)")
        void apiDocs_declaraBearer() throws Exception {
            mvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                    .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                    .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"));
        }

        @Test
        @DisplayName("/swagger-ui/index.html responde 200")
        void swaggerUi_aberto() throws Exception {
            mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        }
    }

    @Nested
    @SpringBootTest(classes = VupthApplication.class)
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
            "springdoc.api-docs.enabled=true",
            "springdoc.swagger-ui.enabled=true",
            "vupth.api-docs.public=false"
    })
    @DisplayName("producao (vupth.api-docs.public=false): a cadeia nega, mesmo com o springdoc ligado")
    class Fechada {

        @Autowired
        MockMvc mvc;

        @Test
        @DisplayName("/v3/api-docs e NEGADO")
        void apiDocs_negado() throws Exception {
            mvc.perform(get("/v3/api-docs")).andExpect(status().is4xxClientError());
            mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("/swagger-ui e NEGADO")
        void swaggerUi_negado() throws Exception {
            mvc.perform(get("/swagger-ui/index.html")).andExpect(status().is4xxClientError());
            mvc.perform(get("/swagger-ui.html")).andExpect(status().is4xxClientError());
        }
    }
}
