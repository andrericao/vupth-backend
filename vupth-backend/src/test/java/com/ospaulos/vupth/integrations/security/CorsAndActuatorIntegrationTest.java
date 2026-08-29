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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — CORS (acesso por link / build web) e a superficie publica do {@code /actuator}.
 *
 * <p><b>O que estes testes protegem.</b> Os dois assuntos falham do mesmo jeito: <b>em silencio</b>.
 * Um CORS errado nao devolve mensagem util — o navegador recusa a resposta e o console mostra o erro
 * generico, sem corpo e sem status; um actuator aberto demais nao quebra nada, so entrega informacao
 * a quem pedir. Nenhum dos dois aparece em teste de logica de negocio.
 *
 * <p>A configuracao de teste expoe <b>metrics de proposito</b>. E a licao do
 * {@code MailHealthIndicatorDisabledTest}: se o endpoint nem estivesse exposto, o assert de "negado"
 * passaria verde pelo motivo errado — inclusive com a regra de seguranca apagada. Aqui ele esta
 * exposto e mesmo assim tem de ser recusado, que e a unica forma de provar que quem barra e a cadeia.
 */
@SpringBootTest(classes = VupthApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "vupth.cors.allowed-origin-patterns=https://vupth-partner.pages.dev,https://*.vupth-partner.pages.dev",
        "vupth.actuator.expose-all=false",
        "management.endpoints.web.exposure.include=health,info,metrics",
        // Sem os probes o /actuator/health/readiness responde 404 e os testes do portao passariam
        // a falhar por ausencia de endpoint, nao por regra de seguranca. Em staging/prod isto ja
        // esta ligado nos .properties do perfil.
        "management.endpoint.health.probes.enabled=true"
})
class CorsAndActuatorIntegrationTest {

    private static final String APEX = "https://vupth-partner.pages.dev";
    private static final String PREVIEW = "https://a1b2c3.vupth-partner.pages.dev";
    private static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";

    @Autowired
    MockMvc mvc;

    @Nested
    @DisplayName("preflight OPTIONS — e aqui que a build web morria antes de 26/08")
    class Preflight {

        /**
         * O teste central: sem {@code .cors()} na cadeia, este OPTIONS caia no
         * {@code anyRequest().authenticated()} e voltava <b>401</b>, porque preflight nao carrega
         * {@code Authorization}. Nenhuma chamada real chegava a acontecer.
         */
        @Test
        @DisplayName("origem autorizada: responde 200 e ecoa a origem — nao 401")
        void origemAutorizada_passa() throws Exception {
            mvc.perform(options("/auth/login")
                            .header("Origin", APEX)
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "authorization,content-type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(ALLOW_ORIGIN, APEX));
        }

        @Test
        @DisplayName("preview do Cloudflare Pages casa pelo curinga de subdominio")
        void preview_casaPeloCuringa() throws Exception {
            mvc.perform(options("/auth/login")
                            .header("Origin", PREVIEW)
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(ALLOW_ORIGIN, PREVIEW));
        }

        @Test
        @DisplayName("origem NAO autorizada nao recebe o cabecalho de liberacao")
        void origemDesconhecida_naoPassa() throws Exception {
            mvc.perform(options("/auth/login")
                            .header("Origin", "https://site-qualquer.example.com")
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(header().doesNotExist(ALLOW_ORIGIN));
        }

        /**
         * ⚠️ O curinga cobre so o subdominio. Se este teste passar a falhar porque alguem "simplificou"
         * a variavel para so o padrao curinga, o apex para de funcionar — e o sintoma seria o link
         * principal quebrado com os previews funcionando, que ninguem associa a configuracao.
         */
        @Test
        @DisplayName("o apex so passa porque esta listado — o curinga sozinho NAO o cobriria")
        void apex_precisaEstarListado() throws Exception {
            mvc.perform(options("/auth/login")
                            .header("Origin", APEX)
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(header().string(ALLOW_ORIGIN, APEX));
        }
    }

    @Nested
    @DisplayName("requisicao real e clientes nativos")
    class RequisicaoReal {

        @Test
        @DisplayName("a resposta real tambem leva o cabecalho — senao o navegador descarta o corpo")
        void respostaReal_levaOCabecalho() throws Exception {
            mvc.perform(post("/auth/login")
                            .header("Origin", APEX)
                            .contentType("application/json")
                            .content("{\"email\":\"nao-existe@example.com\",\"password\":\"x\"}"))
                    .andExpect(header().string(ALLOW_ORIGIN, APEX));
        }

        /**
         * Cliente nativo (os apps Expo) nao manda {@code Origin}: a requisicao nem e CORS, e o filtro
         * passa reto. E o que garante que ligar CORS nao mexeu no que ja estava em producao.
         */
        @Test
        @DisplayName("sem Origin (app nativo) segue normal e sem cabecalho de CORS")
        void semOrigin_naoEhAfetado() throws Exception {
            mvc.perform(get("/actuator/health/readiness"))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(ALLOW_ORIGIN));
        }

        /**
         * O {@code /ws/**} fica FORA da politica de CORS de proposito: o SockJS do Spring emite os
         * proprios cabecalhos a partir do {@code setAllowedOriginPatterns("*")} do
         * {@code WebSocketConfig}. Se o filtro tambem escrevesse os dele, a resposta sairia com
         * {@code Access-Control-Allow-Origin} duplicado e o navegador recusaria — quebrando a fila
         * em tempo real, que hoje funciona.
         */
        @Test
        @DisplayName("o handshake do WebSocket NAO e barrado por origem desconhecida")
        void webSocket_ficaForaDaPolitica() throws Exception {
            mvc.perform(get("/ws/info").header("Origin", "https://site-qualquer.example.com"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("superficie do /actuator — publico so o que tem consumidor conhecido")
    class Actuator {

        @Test
        @DisplayName("/actuator/metrics e NEGADO mesmo estando exposto")
        void metrics_negado() throws Exception {
            mvc.perform(get("/actuator/metrics")).andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("/actuator/info segue publico — o ci.yml le o startedAtEpochMs e os fronts o bootMillis")
        void info_publico() throws Exception {
            mvc.perform(get("/actuator/info")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("/actuator/health/readiness segue publico — e o portao waitForBackend() dos apps")
        void readiness_publico() throws Exception {
            mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        }
    }
}
