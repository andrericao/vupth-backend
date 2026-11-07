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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** sessao-configuravel-do-painel (DEC-187): a sessao do painel por tempo parado, para todos os usuarios. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "vupth.admin.totp.required=true")
class AdminSessionIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;
    @MockBean EmailSender emailSender;

    private final Faker faker = new Faker();

    private record Panel(TestUserUtils.UserAuthAndToken user, String secret, String token, String refresh) {}

    private static String code(String secret) throws Exception {
        var generator = new TimeBasedOneTimePasswordGenerator();
        return String.format("%06d", generator.generateOneTimePassword(
                new SecretKeySpec(AdminTotpService.fromBase32(secret), generator.getAlgorithm()), Instant.now()));
    }

    private static String refreshBody(String token) {
        return "{\"refresh_token\":\"" + token + "\"}";
    }

    /** Conta ADMIN com o 2FA cadastrado; {@code guest} lhe da uma permissao antes do cadastro. */
    private Panel enter(boolean guest) throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(user.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);
        if (guest) {
            jdbc.update("insert into admin_permission (id, user_id, permission) values (?, ?, 'FINANCE')",
                    UUID.randomUUID(), UUID.fromString(user.id()));
        }
        String secret = mapper.readTree(mvc.perform(post("/admin/auth/totp/setup").header("Authorization", user.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("secret").asText();
        JsonNode panel = mapper.readTree(mvc.perform(post("/admin/auth/totp/confirm").header("Authorization", user.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code(secret) + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return new Panel(user, secret, "Bearer " + panel.get("access_token").asText(),
                panel.hasNonNull("refresh_token") ? panel.get("refresh_token").asText() : null);
    }

    private JsonNode refresh(String token) throws Exception {
        return mapper.readTree(mvc.perform(post("/admin/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void refreshRefused(String token, String expectedCode) throws Exception {
        mvc.perform(post("/admin/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshBody(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(expectedCode));
    }

    @Test
    @DisplayName("quem entrou renova sem senha nem codigo, sem e-mail de aviso; o token novo abre o painel e o refresh troca a cada uso")
    void ownerRenews() throws Exception {
        Panel panel = enter(false);
        assertThat(panel.refresh()).isNotBlank();
        clearInvocations(emailSender);

        JsonNode renewed = refresh(panel.refresh());

        assertThat(renewed.get("expires_in").asInt()).isEqualTo(900);
        assertThat(renewed.get("session_expires_in").asLong()).isBetween(479 * 60L, 480 * 60L);
        assertThat(renewed.get("refresh_token").asText()).isNotEqualTo(panel.refresh());
        mvc.perform(get("/admin/users").header("Authorization", "Bearer " + renewed.get("access_token").asText()))
                .andExpect(status().isOk());
        verify(emailSender, never()).send(any(), contains("painel"), any());
    }

    @Test
    @DisplayName("token velho com Authorization vencido junto nao vira 401 do JWT: quem prova e o refresh")
    void staleBearerDoesNotBlock() throws Exception {
        Panel panel = enter(false);
        mvc.perform(post("/admin/auth/refresh").header("Authorization", "Bearer vencido")
                        .contentType(MediaType.APPLICATION_JSON).content(refreshBody(panel.refresh())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("duas abas: o refresh anterior vale na folga, sem trocar; passada a folga, reuso encerra a sessao")
    void previousTokenGraceAndReuse() throws Exception {
        Panel panel = enter(false);
        String current = refresh(panel.refresh()).get("refresh_token").asText();

        JsonNode secondTab = refresh(panel.refresh());
        assertThat(secondTab.hasNonNull("access_token")).isTrue();
        assertThat(secondTab.has("refresh_token")).isFalse();

        jdbc.update("update admin_session set rotated_at = ? where user_id = ?", OffsetDateTime.now().minusMinutes(1),
                UUID.fromString(panel.user().id()));
        refreshRefused(panel.refresh(), "ADMIN_SESSION_INVALID");
        refreshRefused(current, "ADMIN_SESSION_EXPIRED");
    }

    @Test
    @DisplayName("tempo parado: passou a duracao sem uso, acabou; o uso renova; o teto de 30 dias vale mesmo em uso")
    void idleAndHardLimit() throws Exception {
        Panel panel = enter(false);
        UUID id = UUID.fromString(panel.user().id());

        jdbc.update("update admin_session set last_used_at = ? where user_id = ?", OffsetDateTime.now().minusHours(7), id);
        String next = refresh(panel.refresh()).get("refresh_token").asText();

        jdbc.update("update admin_session set started_at = ? where user_id = ?", OffsetDateTime.now().minusDays(30).plusMinutes(10), id);
        assertThat(refresh(next).get("session_expires_in").asLong()).isLessThanOrEqualTo(600);

        Panel idle = enter(false);
        jdbc.update("update admin_session set last_used_at = ? where user_id = ?", OffsetDateTime.now().minusHours(9),
                UUID.fromString(idle.user().id()));
        refreshRefused(idle.refresh(), "ADMIN_SESSION_EXPIRED");

        Panel old = enter(false);
        jdbc.update("update admin_session set started_at = ? where user_id = ?", OffsetDateTime.now().minusDays(31),
                UUID.fromString(old.user().id()));
        refreshRefused(old.refresh(), "ADMIN_SESSION_EXPIRED");
    }

    @Test
    @DisplayName("a sessao vale para o convidado tambem; a escolha da duracao e so do dono")
    void guestRenewsButDoesNotChooseDuration() throws Exception {
        Panel guest = enter(true);

        JsonNode renewed = refresh(guest.refresh());
        assertThat(renewed.hasNonNull("access_token")).isTrue();
        mvc.perform(get("/admin/session-settings").header("Authorization", guest.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_PERMISSION_DENIED"));
        mvc.perform(put("/admin/session-settings").header("Authorization", guest.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"idle_minutes\":60}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("conta desativada perde a sessao no proximo refresh")
    void deactivatedAccount() throws Exception {
        Panel panel = enter(false);
        BaseUser base = userRepository.findById(UUID.fromString(panel.user().id())).orElseThrow();
        base.setIsActive(false);
        userRepository.save(base);

        refreshRefused(panel.refresh(), "ADMIN_SESSION_EXPIRED");
    }

    @Test
    @DisplayName("sair encerra a sessao; refresh desconhecido e 401; sair sem token nao e erro")
    void logout() throws Exception {
        Panel panel = enter(false);

        mvc.perform(post("/admin/auth/logout").contentType(MediaType.APPLICATION_JSON).content(refreshBody(panel.refresh())))
                .andExpect(status().isNoContent());
        refreshRefused(panel.refresh(), "ADMIN_SESSION_EXPIRED");
        refreshRefused("desconhecido", "ADMIN_SESSION_INVALID");
        mvc.perform(post("/admin/auth/logout")).andExpect(status().isNoContent());
        mvc.perform(post("/admin/auth/refresh")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_SESSION_INVALID"));
    }

    @Test
    @DisplayName("trocar a senha derruba a sessao do painel")
    void passwordChangeClosesSession() throws Exception {
        Panel panel = enter(false);
        String newPassword = "Nova@Senha123";

        mvc.perform(patch("/profile/update-password/me").header("Authorization", panel.user().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(java.util.Map.of("current_password", panel.user().password(),
                                "password", newPassword))))
                .andExpect(status().is2xxSuccessful());

        refreshRefused(panel.refresh(), "ADMIN_SESSION_EXPIRED");
    }

    @Test
    @DisplayName("duracao: padrao 8 h, so com o token do painel, so as opcoes da lista, e vale para a proxima entrada")
    void durationSetting() throws Exception {
        Panel panel = enter(false);
        try {
            mvc.perform(get("/admin/session-settings").header("Authorization", panel.token()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.idle_minutes").value(480))
                    .andExpect(jsonPath("$.options.length()").value(5))
                    .andExpect(jsonPath("$.max_days").value(30));
            mvc.perform(put("/admin/session-settings").header("Authorization", panel.user().token())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"idle_minutes\":60}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("ADMIN_TOTP_REQUIRED"));
            mvc.perform(put("/admin/session-settings").header("Authorization", panel.token())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"idle_minutes\":61}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ADMIN_SESSION_DURATION_INVALID"));
            mvc.perform(put("/admin/session-settings").header("Authorization", panel.token())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"idle_minutes\":60}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.idle_minutes").value(60));

            assertThat(refresh(panel.refresh()).get("session_expires_in").asLong()).isGreaterThan(60 * 60L);
            assertThat(refresh(enter(false).refresh()).get("session_expires_in").asLong()).isLessThanOrEqualTo(60 * 60L);
        } finally {
            jdbc.update("delete from app_setting where setting_key = 'admin.session.idle_minutes'");
        }
    }
}
