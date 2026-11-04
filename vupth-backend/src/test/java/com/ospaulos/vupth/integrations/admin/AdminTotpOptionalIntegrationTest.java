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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * segundo-fator-opcional-ate-cadastrar (DEC-177): com a chave global desligada, o ADMIN sem fator entra so com a senha e pode
 * cadastrar quando quiser; depois de cadastrar, o codigo e SEMPRE exigido (o token do login comum deixa de abrir o painel).
 */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "vupth.admin.totp.required=false")
class AdminTotpOptionalIntegrationTest {

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

    @Test
    @DisplayName("DEC-177: sem fator entra com a senha; cadastrado, o token do login comum recebe 401 ADMIN_TOTP_REQUIRED e so o do painel abre")
    void optionalUntilEnrolledThenAlways() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(user.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);

        // (1) sem fator: entra so com a senha, sem ser empurrado para o QR
        mvc.perform(get("/admin/users").header("Authorization", user.token())).andExpect(status().isOk());

        // cadastra quando quiser, ja logado
        JsonNode setup = mapper.readTree(mvc.perform(post("/admin/auth/totp/setup").header("Authorization", user.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String secret = setup.get("secret").asText();
        // cadastro iniciado e nao confirmado ainda nao exige o codigo
        mvc.perform(get("/admin/users").header("Authorization", user.token())).andExpect(status().isOk());
        JsonNode confirmed = mapper.readTree(mvc.perform(post("/admin/auth/totp/confirm").header("Authorization", user.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code(secret) + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String panelToken = "Bearer " + confirmed.get("access_token").asText();

        // (2) com fator: o token do login comum nao abre mais, mesmo com a chave global desligada
        mvc.perform(get("/admin/users").header("Authorization", user.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_TOTP_REQUIRED"));
        mvc.perform(get("/admin/auth/status").header("Authorization", user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totp_enabled").value(true));
        mvc.perform(get("/admin/users").header("Authorization", panelToken)).andExpect(status().isOk());
    }
}
