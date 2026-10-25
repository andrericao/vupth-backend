package com.ospaulos.vupth.integrations.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import com.ospaulos.vupth.infrastructure.security.jwt.token.JwtTokenProvider;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * token-de-teste-sandbox: o token de ADMIN de 7 dias so existe ligado (TEST_TOKEN_ENABLED) E com o Asaas em sandbox; abre uma lista
 * curta de rotas; um token de teste nao emite outro; revogavel; no modo real ou desligado e recusado (401).
 */
class TestTokenIntegrationTest {

    abstract static class Base {
        @Autowired MockMvc mvc;
        @Autowired ObjectMapper mapper;
        @Autowired UserRepository userRepository;
        @Autowired JwtTokenProvider provider;
        @Autowired org.springframework.security.oauth2.jwt.JwtEncoder jwtEncoder;
        final Faker faker = new Faker();

        BaseUser adminUser;

        String adminToken() throws Exception {
            var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
            adminUser = userRepository.findById(UUID.fromString(user.id())).orElseThrow();
            adminUser.addRole(RoleType.ADMIN);
            adminUser = userRepository.save(adminUser);
            return user.token();
        }

        /** Um token de teste montado direto pelo provedor: prova a recusa mesmo sem a rota de emissao. */
        String forgedTestToken() throws Exception {
            adminToken();
            return "Bearer " + provider.createTestToken(new UserDetailsImpl(adminUser), UUID.randomUUID(), 0L);
        }

        JSONObject issue(String admin) throws Exception {
            return new JSONObject(mvc.perform(post("/admin/test-tokens").header("Authorization", admin))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.valid_days").value(7))
                    .andReturn().getResponse().getContentAsString());
        }
    }

    @Nested
    @SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"TEST_TOKEN_ENABLED=true", "ASAAS_API_KEY="})
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @DisplayName("ligado e em sandbox")
    class Enabled extends Base {

        @Test
        @DisplayName("o ADMIN emite; o token abre a lista curta e so ela; um token de teste nao emite outro")
        void issueAndScope() throws Exception {
            String admin = adminToken();
            JSONObject issued = issue(admin);
            String test = "Bearer " + issued.getString("token");

            mvc.perform(get("/admin/finance/app-balance").header("Authorization", test)).andExpect(status().isOk());
            mvc.perform(get("/admin/settings/asaas-connection").header("Authorization", test)).andExpect(status().isOk());
            mvc.perform(get("/settings/service-min-price").header("Authorization", test)).andExpect(status().isOk());
            mvc.perform(put("/admin/settings/asaas-connection").header("Authorization", test)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"connected\":false}"))
                    .andExpect(status().isOk());

            mvc.perform(get("/admin/overview").header("Authorization", test))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TEST_TOKEN_SCOPE"));
            mvc.perform(get("/clients/me/absences").header("Authorization", test))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TEST_TOKEN_SCOPE"));
            mvc.perform(post("/admin/test-tokens").header("Authorization", test))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TEST_TOKEN_SCOPE"));
            mvc.perform(delete("/admin/test-tokens").header("Authorization", test))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TEST_TOKEN_SCOPE"));
        }

        @Test
        @DisplayName("revogar um (jti) derruba esse token (401) e nao os outros; revogar todos derruba os emitidos ate agora")
        void revoke() throws Exception {
            String admin = adminToken();
            JSONObject first = issue(admin);
            JSONObject second = issue(admin);
            String firstToken = "Bearer " + first.getString("token");
            String secondToken = "Bearer " + second.getString("token");
            mvc.perform(get("/admin/finance/app-balance").header("Authorization", firstToken)).andExpect(status().isOk());

            mvc.perform(delete("/admin/test-tokens/{jti}", first.getString("jti")).header("Authorization", admin))
                    .andExpect(status().isNoContent());
            mvc.perform(get("/admin/finance/app-balance").header("Authorization", firstToken)).andExpect(status().isUnauthorized());
            mvc.perform(get("/admin/finance/app-balance").header("Authorization", secondToken)).andExpect(status().isOk());

            mvc.perform(delete("/admin/test-tokens").header("Authorization", admin)).andExpect(status().isNoContent());
            mvc.perform(get("/admin/finance/app-balance").header("Authorization", secondToken)).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("token vencido: 401 com o motivo TOKEN_EXPIRED no corpo (nao fica mais 401 mudo)")
        void expiredTokenSaysWhy() throws Exception {
            adminToken();
            java.time.Instant now = java.time.Instant.now();
            var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                    .issuer("vupth-backend").subject(adminUser.getId().toString())
                    .issuedAt(now.minusSeconds(7200)).expiresAt(now.minusSeconds(3600))
                    .claim("email", adminUser.getEmail()).claim("roles", "ROLE_ADMIN").build();
            String expired = jwtEncoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(claims)).getTokenValue();
            mvc.perform(post("/admin/test-tokens").header("Authorization", "Bearer " + expired))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
        }

        @Test
        @DisplayName("quem nao e ADMIN nao emite nem revoga (403); sem login, 401")
        void onlyAdmin() throws Exception {
            var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
            mvc.perform(post("/admin/test-tokens").header("Authorization", client.token())).andExpect(status().isForbidden());
            mvc.perform(delete("/admin/test-tokens").header("Authorization", client.token())).andExpect(status().isForbidden());
            mvc.perform(post("/admin/test-tokens")).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("TOKEN_MISSING"));
            mvc.perform(post("/admin/test-tokens").header("Authorization", "Bearer token.malformado.xxx"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        }
    }

    @Nested
    @SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"ASAAS_API_KEY="})
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @DisplayName("desligado (padrao)")
    class Disabled extends Base {

        @Test
        @DisplayName("nao emite (403 TEST_TOKEN_DISABLED) e um token de teste montado e recusado (401)")
        void refused() throws Exception {
            String admin = adminToken();
            mvc.perform(post("/admin/test-tokens").header("Authorization", admin))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TEST_TOKEN_DISABLED"));
            mvc.perform(get("/admin/finance/app-balance").header("Authorization", forgedTestToken()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("TEST_TOKEN_REFUSED"));
        }
    }

    @Nested
    @SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"TEST_TOKEN_ENABLED=true", "ASAAS_API_KEY=", "ASAAS_BASE_URL=https://api.asaas.com/v3"})
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @DisplayName("ligado, mas o Asaas em modo real")
    class RealMode extends Base {

        @Test
        @DisplayName("no modo real nao emite (403) e o token de teste e recusado (401): ele some")
        void refusedInRealMode() throws Exception {
            String admin = adminToken();
            mvc.perform(post("/admin/test-tokens").header("Authorization", admin))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TEST_TOKEN_DISABLED"));
            mvc.perform(get("/admin/finance/app-balance").header("Authorization", forgedTestToken()))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get("/admin/finance/app-balance").header("Authorization", admin)).andExpect(status().isOk());
        }
    }
}
