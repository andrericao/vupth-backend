package com.ospaulos.vupth.integrations.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.domain.access.model.AccessEvent;
import com.ospaulos.vupth.domain.access.model.AccessLog;
import com.ospaulos.vupth.domain.access.repository.AccessLogRepository;
import com.ospaulos.vupth.domain.access.service.AccessLogService;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — registro de acesso (Marco Civil, art. 15; registro-de-acesso, 10/10): o login e o
 * refresh gravam usuario, IP, porta e hora; o que passa de 6 meses sai na limpeza.
 */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccessLogIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AccessLogRepository accessLogRepository;
    @Autowired AccessLogService accessLogService;

    private final Faker faker = new Faker();

    @Test
    @DisplayName("Login e refresh gravam o acesso com o IP e a porta da conexao")
    void loginAndRefreshAreRecorded() throws Exception {
        var user = TestUserUtils.registerBaseUser(mvc, mapper, faker);
        UUID userId = UUID.fromString(user.id());

        String body = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(user.email(), user.password())))
                        .with(r -> { r.setRemoteAddr("177.10.0.1"); r.setRemotePort(51000); return r; }))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String refreshToken = new JSONObject(body).getJSONObject("tokens").getString("refresh_token");

        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk());

        List<AccessLog> logs = accessLogRepository.findByUserIdOrderByCreatedAtDesc(userId);
        assertThat(logs).extracting(AccessLog::getEvent).containsExactlyInAnyOrder(AccessEvent.LOGIN, AccessEvent.REFRESH);
        AccessLog login = logs.stream().filter(l -> l.getEvent() == AccessEvent.LOGIN).findFirst().orElseThrow();
        assertThat(login.getIp()).isEqualTo("177.10.0.1");
        assertThat(login.getPort()).isEqualTo(51000);
        assertThat(login.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Login que falha nao grava nada")
    void failedLoginIsNotRecorded() throws Exception {
        var user = TestUserUtils.registerBaseUser(mvc, mapper, faker);

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(user.email(), "senha-errada-123"))))
                .andExpect(status().is4xxClientError());

        assertThat(accessLogRepository.findByUserIdOrderByCreatedAtDesc(UUID.fromString(user.id()))).isEmpty();
    }

    @Test
    @DisplayName("Limpeza: o que tem mais de 6 meses sai; o resto fica")
    void purgeKeepsSixMonths() {
        UUID userId = UUID.randomUUID();
        accessLogRepository.save(new AccessLog(userId, AccessEvent.LOGIN, "1.2.3.4", null,
                OffsetDateTime.now().minusMonths(6).minusDays(1)));
        accessLogRepository.save(new AccessLog(userId, AccessEvent.LOGIN, "1.2.3.4", null,
                OffsetDateTime.now().minusMonths(5)));

        accessLogService.purgeExpired();

        assertThat(accessLogRepository.findByUserIdOrderByCreatedAtDesc(userId)).hasSize(1);
    }
}
