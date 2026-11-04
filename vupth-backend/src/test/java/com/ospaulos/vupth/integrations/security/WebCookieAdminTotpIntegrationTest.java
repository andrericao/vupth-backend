package com.ospaulos.vupth.integrations.security;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.domain.admin.service.AdminTotpService;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DEC-179 (pedido do Painel): no modo cookie, o token do painel (depois do 2o fator) vai so no cookie HttpOnly de acesso. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"vupth.auth.web-cookie.enabled=true", "vupth.admin.totp.required=true"})
class WebCookieAdminTotpIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    private final Faker faker = new Faker();

    private static String code(String secret) throws Exception {
        var g = new TimeBasedOneTimePasswordGenerator();
        return String.format("%06d", g.generateOneTimePassword(
                new SecretKeySpec(AdminTotpService.fromBase32(secret), g.getAlgorithm()), Instant.now()));
    }

    @Test
    @DisplayName("login e 2o fator no modo cookie: o token do painel vem no vupth_at (fora do corpo) e abre o /admin; o token comum nao")
    void panelTokenInCookie() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(user.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);

        MvcResult login = mvc.perform(post("/auth/login").header("X-Auth-Mode", "cookie").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(user.email(), user.password()))))
                .andExpect(status().isOk()).andReturn();
        Map<String, String> c = WebCookieTestSupport.cookies(login);
        Cookie at = new Cookie("vupth_at", c.get("vupth_at"));
        Cookie csrf = new Cookie("vupth_csrf", c.get("vupth_csrf"));

        mvc.perform(get("/admin/users").cookie(at)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_TOTP_REQUIRED"));

        String secret = mapper.readTree(mvc.perform(post("/admin/auth/totp/setup").cookie(at, csrf)
                        .header("X-CSRF-Token", csrf.getValue()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("secret").asText();
        MvcResult confirmed = mvc.perform(post("/admin/auth/totp/confirm").cookie(at, csrf)
                        .header("X-CSRF-Token", csrf.getValue()).header("X-Auth-Mode", "cookie")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code(secret) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").doesNotExist())
                .andExpect(jsonPath("$.recovery_codes.length()").value(8))
                .andReturn();
        String panel = WebCookieTestSupport.cookies(confirmed).get("vupth_at");
        assertThat(panel).isNotBlank().isNotEqualTo(c.get("vupth_at"));
        assertThat(confirmed.getResponse().getHeaders("Set-Cookie")).singleElement().asString().contains("HttpOnly", "Max-Age=900");

        mvc.perform(get("/admin/users").cookie(new Cookie("vupth_at", panel))).andExpect(status().isOk());
    }
}
