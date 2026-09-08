package com.ospaulos.vupth.integrations.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — <b>sessoes simultaneas e a corrida de refresh</b> (2026-09-06).
 *
 * <p>Os dois defeitos que estes testes travam sao invisiveis do lado de fora: o app so consegue
 * dizer <i>"sessao expirada"</i>, e quem olha o backend ve um 401 legitimo.</p>
 *
 * <ol>
 *   <li><b>Uma sessao por conta.</b> {@code createRefreshToken} comecava apagando TODOS os tokens do
 *       usuario, e o login passa por ali — entrar num aparelho derrubava o outro. Aqui isso importa
 *       mais que na media dos produtos: os papeis se acumulam, entao a mesma pessoa usa o Client e o
 *       Partner, e um app derrubava o outro a cada troca.</li>
 *   <li><b>A corrida de refresh.</b> O app dispara varios refresh no mesmo instante (o log de PROD
 *       mostrou quatro). Com rotacao, so o primeiro pode ganhar; sem a janela de graca os outros
 *       levam 401 e o usuario cai fora.</li>
 * </ol>
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MultiDeviceSessionIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private final Faker faker = new Faker();

    private String email;
    private String password;

    @BeforeAll
    void setup() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.email = user.email();
        this.password = user.password();
    }

    /** Um login = um "aparelho": o par de tokens daquela sessao. */
    private record Sessao(String accessToken, String refreshToken) {}

    private Sessao login() throws Exception {
        MvcResult login = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();
        JSONObject tokens = new JSONObject(login.getResponse().getContentAsString())
                .getJSONObject("tokens");
        return new Sessao(tokens.getString("access_token"), tokens.getString("refresh_token"));
    }

    private String loginAndGetRefreshToken() throws Exception {
        return login().refreshToken();
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + refreshToken + "\"}"))
                .andReturn();
    }

    @Test
    @Order(10)
    @DisplayName("logar no segundo aparelho NAO derruba a sessao do primeiro")
    void secondLoginDoesNotKillTheFirstSession() throws Exception {
        String aparelhoA = loginAndGetRefreshToken();
        String aparelhoB = loginAndGetRefreshToken();

        assertThat(aparelhoA).isNotEqualTo(aparelhoB);

        // O aparelho A nao tocou em nada desde que B entrou — a sessao dele tem de continuar viva.
        MvcResult respostaA = refresh(aparelhoA);
        assertThat(respostaA.getResponse().getStatus())
                .as("entrar no Partner nao pode deslogar o Client da mesma pessoa")
                .isEqualTo(200);

        MvcResult respostaB = refresh(aparelhoB);
        assertThat(respostaB.getResponse().getStatus())
                .as("e o aparelho que entrou por ultimo tambem segue valendo")
                .isEqualTo(200);
    }

    @Test
    @Order(20)
    @DisplayName("dois refresh com o MESMO token: o segundo recebe o sucessor, nao 401")
    void concurrentRefreshIsAbsorbedByTheGraceWindow() throws Exception {
        String token = loginAndGetRefreshToken();

        MvcResult primeira = refresh(token);
        assertThat(primeira.getResponse().getStatus()).isEqualTo(200);
        String sucessor = new JSONObject(primeira.getResponse().getContentAsString())
                .getString("refresh_token");

        // A segunda chamada da corrida chega com o token que a primeira acabou de revogar.
        MvcResult segunda = refresh(token);
        assertThat(segunda.getResponse().getStatus())
                .as("a corrida nao pode deslogar o usuario")
                .isEqualTo(200);

        String recebido = new JSONObject(segunda.getResponse().getContentAsString())
                .getString("refresh_token");
        assertThat(recebido)
                .as("as duas chamadas convergem para o MESMO par — nao para dois")
                .isEqualTo(sucessor);
    }

    @Test
    @Order(30)
    @DisplayName("o token entregue pela janela de graca continua servindo para rotacionar")
    void theSuccessorStillWorksAfterTheGraceWindow() throws Exception {
        String token = loginAndGetRefreshToken();

        String sucessor = new JSONObject(refresh(token).getResponse().getContentAsString())
                .getString("refresh_token");

        MvcResult proxima = refresh(sucessor);
        assertThat(proxima.getResponse().getStatus())
                .as("a sessao segue rotacionando normalmente depois da corrida")
                .isEqualTo(200);
    }

    @Test
    @Order(35)
    @DisplayName("logout com token JA ROTACIONADO tambem encerra a sessao (nao vira no-op)")
    void logoutWithStaleTokenStillEndsTheSession() throws Exception {
        Sessao aparelho = login();

        // O app rotacionou e, logo depois, mandou sair com o token ANTIGO — o caso que a corrida de
        // refresh produz sozinha. Antes disso ser tratado, o logout revogava "por token" numa linha
        // ja revogada: nao fazia nada, e o sucessor seguia vivo. O usuario pedia para sair e nao saia.
        String sucessor = new JSONObject(refresh(aparelho.refreshToken()).getResponse().getContentAsString())
                .getString("refresh_token");

        mvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + aparelho.accessToken())
                        .content("{\"refresh_token\":\"" + aparelho.refreshToken() + "\"}"))
                .andExpect(status().is2xxSuccessful());

        assertThat(refresh(sucessor).getResponse().getStatus())
                .as("o sucessor cai junto — logout encerra a SESSAO, nao um token")
                .isEqualTo(401);
        assertThat(refresh(aparelho.refreshToken()).getResponse().getStatus())
                .as("e o velho tambem nao ressuscita pela janela de graca")
                .isEqualTo(401);
    }

    @Test
    @Order(40)
    @DisplayName("logout derruba SO a sessao daquele aparelho")
    void logoutOnlyEndsThatDevice() throws Exception {
        Sessao aparelhoA = login();
        String aparelhoB = loginAndGetRefreshToken();

        // O logout e endpoint autenticado: vai o access token do aparelho que esta saindo.
        mvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + aparelhoA.accessToken())
                        .content("{\"refresh_token\":\"" + aparelhoA.refreshToken() + "\"}"))
                .andExpect(status().is2xxSuccessful());

        assertThat(refresh(aparelhoA.refreshToken()).getResponse().getStatus())
                .as("o aparelho que saiu perde a sessao — e sem sucessor, a janela de graca nao vale")
                .isEqualTo(401);
        assertThat(refresh(aparelhoB).getResponse().getStatus())
                .as("o outro aparelho continua logado")
                .isEqualTo(200);
    }
}
