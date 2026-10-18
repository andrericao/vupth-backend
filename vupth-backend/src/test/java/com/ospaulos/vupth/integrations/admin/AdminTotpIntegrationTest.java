package com.ospaulos.vupth.integrations.admin;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.admin.service.AdminTotpService;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** painel, fatia 3: o painel so abre com o token do segundo fator (TOTP ou codigo de recuperacao). */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "vupth.admin.totp.required=true")
class AdminTotpIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @MockBean EmailSender emailSender;

    private final Faker faker = new Faker();

    private static String code(String secret) throws Exception {
        var generator = new TimeBasedOneTimePasswordGenerator();
        return String.format("%06d", generator.generateOneTimePassword(
                new SecretKeySpec(AdminTotpService.fromBase32(secret), generator.getAlgorithm()), Instant.now()));
    }

    private static String body(String code) {
        return "{\"code\":\"" + code + "\"}";
    }

    @Test
    @DisplayName("sem o 2o fator: 401 ADMIN_TOTP_REQUIRED; cadastro, confirmacao, token do painel, recuperacao e aviso por e-mail")
    void fullFlow() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(user.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);

        // o token do login comum nao abre o painel, mas abre a area de autenticacao
        mvc.perform(get("/admin/users").header("Authorization", user.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_TOTP_REQUIRED"));
        mvc.perform(get("/admin/auth/status").header("Authorization", user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totp_enabled").value(false))
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.owner").value(true))
                .andExpect(jsonPath("$.permissions.length()").value(9));
        mvc.perform(post("/admin/auth/totp/verify").header("Authorization", user.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body("123456")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_TOTP_NOT_SET_UP"));

        // cadastro: segredo -> codigo errado nao ativa -> codigo certo ativa e entrega o token e os 8 codigos
        JsonNode setup = mapper.readTree(mvc.perform(post("/admin/auth/totp/setup")
                        .header("Authorization", user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otpauth_uri").isNotEmpty())
                .andReturn().getResponse().getContentAsString());
        String secret = setup.get("secret").asText();
        mvc.perform(post("/admin/auth/totp/confirm").header("Authorization", user.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body("000000")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_TOTP_INVALID"));
        JsonNode confirmed = mapper.readTree(mvc.perform(post("/admin/auth/totp/confirm")
                        .header("Authorization", user.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body(code(secret))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recovery_codes.length()").value(8))
                .andExpect(jsonPath("$.expires_in").value(900))
                .andReturn().getResponse().getContentAsString());
        String adminToken = "Bearer " + confirmed.get("access_token").asText();
        String recovery = confirmed.get("recovery_codes").get(0).asText();

        // o token do painel abre o /admin; o setup de novo e 409
        mvc.perform(get("/admin/users").header("Authorization", adminToken)).andExpect(status().isOk());
        mvc.perform(get("/admin/auth/status").header("Authorization", adminToken))
                .andExpect(jsonPath("$.totp_enabled").value(true))
                .andExpect(jsonPath("$.verified").value(true));
        mvc.perform(post("/admin/auth/totp/setup").header("Authorization", adminToken))
                .andExpect(status().isConflict());

        // nas proximas vezes: o codigo do app ou um de recuperacao (uma vez so)
        mvc.perform(post("/admin/auth/totp/verify").header("Authorization", user.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body(code(secret))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.recovery_codes").doesNotExist());
        mvc.perform(post("/admin/auth/totp/verify").header("Authorization", user.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body(recovery)))
                .andExpect(status().isOk());
        mvc.perform(post("/admin/auth/totp/verify").header("Authorization", user.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body(recovery)))
                .andExpect(status().isUnauthorized());

        verify(emailSender, atLeastOnce()).send(eq(user.email()), contains("painel"), contains("acesso"));
        assertThat(user.email()).isNotBlank();
    }

    @Test
    @DisplayName("5 codigos errados travam: 429 ADMIN_TOTP_LOCKED, ate com o codigo certo")
    void lockout() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(user.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);
        String secret = mapper.readTree(mvc.perform(post("/admin/auth/totp/setup").header("Authorization", user.token()))
                .andReturn().getResponse().getContentAsString()).get("secret").asText();
        mvc.perform(post("/admin/auth/totp/confirm").header("Authorization", user.token())
                .contentType(MediaType.APPLICATION_JSON).content(body(code(secret)))).andExpect(status().isOk());

        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/admin/auth/totp/verify").header("Authorization", user.token())
                    .contentType(MediaType.APPLICATION_JSON).content(body("000000")))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/admin/auth/totp/verify").header("Authorization", user.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body(code(secret))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ADMIN_TOTP_LOCKED"));
    }
}
