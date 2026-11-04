package com.ospaulos.vupth.integrations.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DEC-179 com a chave ligada. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"vupth.auth.web-cookie.enabled=true", "vupth.cors.allowed-origin-patterns=" + WebCookieTestSupport.WEB})
@DisplayName("chave ligada")
class WebCookieAuthEnabledIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    private final Faker faker = new Faker();

    @Test
    @DisplayName("login no modo cookie: tokens so nos cookies (HttpOnly, Secure); leitura pelo cookie; escrita sem CSRF 403, com CSRF passa; refresh e logout da web")
    void fullCycle() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        MvcResult login = mvc.perform(post("/auth/login").header("X-Auth-Mode", "cookie")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(user.email(), user.password()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokens").doesNotExist())
                .andExpect(jsonPath("$.user.id").value(user.id()))
                .andReturn();
        List<String> headers = login.getResponse().getHeaders("Set-Cookie");
        assertThat(headers).hasSize(3);
        assertThat(headers).filteredOn(h -> h.startsWith("vupth_at=")).singleElement().asString()
                .contains("HttpOnly", "Secure", "SameSite=None", "Partitioned", "Path=/");
        assertThat(headers).filteredOn(h -> h.startsWith("vupth_rt=")).singleElement().asString()
                .contains("HttpOnly", "Path=/auth/web");
        assertThat(headers).filteredOn(h -> h.startsWith("vupth_csrf=")).singleElement().asString()
                .doesNotContain("HttpOnly");
        Map<String, String> c = WebCookieTestSupport.cookies(login);
        Cookie at = new Cookie("vupth_at", c.get("vupth_at"));
        Cookie csrf = new Cookie("vupth_csrf", c.get("vupth_csrf"));

        // leitura so com o cookie
        mvc.perform(get("/clients/me/pending-penalties").cookie(at)).andExpect(status().isOk());
        // escrita sem o cabecalho de CSRF: 403; com o cabecalho errado: 403; com o certo: passa
        mvc.perform(post("/devices").cookie(at, csrf).contentType(MediaType.APPLICATION_JSON).content(WebCookieTestSupport.device()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post("/devices").cookie(at, csrf).header("X-CSRF-Token", "outro")
                        .contentType(MediaType.APPLICATION_JSON).content(WebCookieTestSupport.device()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/devices").cookie(at, csrf).header("X-CSRF-Token", csrf.getValue()).header("Origin", WebCookieTestSupport.WEB)
                        .contentType(MediaType.APPLICATION_JSON).content(WebCookieTestSupport.device()))
                .andExpect(status().isOk());
        // S18 (DEC-180): com o token certo, mas sem Origin ou de origem fora da lista: 403
        mvc.perform(post("/devices").cookie(at, csrf).header("X-CSRF-Token", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(WebCookieTestSupport.device()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORIGIN_INVALID"));
        mvc.perform(post("/devices").cookie(at, csrf).header("X-CSRF-Token", csrf.getValue()).header("Origin", "https://outro.site")
                        .contentType(MediaType.APPLICATION_JSON).content(WebCookieTestSupport.device()))
                .andExpect(status().isForbidden());
        // o nativo (Bearer) escreve sem CSRF, como sempre
        mvc.perform(post("/devices").header("Authorization", user.token())
                .contentType(MediaType.APPLICATION_JSON).content(WebCookieTestSupport.device())).andExpect(status().isOk());

        // refresh da web: pelo cookie, com CSRF; devolve cookies novos
        Cookie rt = new Cookie("vupth_rt", c.get("vupth_rt"));
        mvc.perform(post("/auth/web/refresh").cookie(rt, csrf)).andExpect(status().isForbidden());
        MvcResult refreshed = mvc.perform(post("/auth/web/refresh").cookie(rt, csrf).header("X-CSRF-Token", csrf.getValue()).header("Origin", WebCookieTestSupport.WEB))
                .andExpect(status().isNoContent()).andReturn();
        Map<String, String> c2 = WebCookieTestSupport.cookies(refreshed);
        assertThat(c2.get("vupth_rt")).isNotBlank().isNotEqualTo(c.get("vupth_rt"));
        Cookie rt2 = new Cookie("vupth_rt", c2.get("vupth_rt"));
        Cookie csrf2 = new Cookie("vupth_csrf", c2.get("vupth_csrf"));

        // logout da web: encerra a sessao e apaga os cookies; o refresh depois falha
        MvcResult out = mvc.perform(post("/auth/web/logout").cookie(rt2, csrf2).header("X-CSRF-Token", csrf2.getValue()).header("Origin", WebCookieTestSupport.WEB))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(out.getResponse().getHeaders("Set-Cookie")).hasSize(3).allMatch(h -> h.contains("Max-Age=0"));
        int after = mvc.perform(post("/auth/web/refresh").cookie(rt2, csrf2).header("X-CSRF-Token", csrf2.getValue()).header("Origin", WebCookieTestSupport.WEB))
                .andReturn().getResponse().getStatus();
        assertThat(after).isIn(401, 403);

        // custo medido (DEC-179): o que a web passa a mandar por pedido
        System.out.printf("[DEC-179] Cookie por pedido: vupth_at=%d + vupth_csrf=%d bytes; X-CSRF-Token=%d bytes nas escritas%n",
                c.get("vupth_at").length(), c.get("vupth_csrf").length(), c.get("vupth_csrf").length());
    }

    @Test
    @DisplayName("sem pedir o modo cookie, o login segue igual (tokens no corpo, nenhum cookie)")
    void withoutHeaderUnchanged() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        MvcResult r = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(user.email(), user.password()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tokens.access_token").isNotEmpty()).andReturn();
        assertThat(r.getResponse().getHeaders("Set-Cookie")).isEmpty();
    }
}
