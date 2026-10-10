package com.ospaulos.vupth.domain.access;

import com.ospaulos.vupth.domain.access.model.AccessEvent;
import com.ospaulos.vupth.domain.access.model.AccessLog;
import com.ospaulos.vupth.domain.access.repository.AccessLogRepository;
import com.ospaulos.vupth.domain.access.service.AccessLogService;
import com.ospaulos.vupth.domain.refreshToken.model.RefreshToken;
import com.ospaulos.vupth.domain.refreshToken.repository.RefreshTokenRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Unit tests — o registro de acesso por 6 meses (Marco Civil, art. 15; registro-de-acesso). */
class AccessLogServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private AccessLogRepository accessLogRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private AccessLogService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        accessLogRepository = mock(AccessLogRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        service = new AccessLogService(accessLogRepository, refreshTokenRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AccessLog saved() {
        ArgumentCaptor<AccessLog> captor = ArgumentCaptor.forClass(AccessLog.class);
        verify(accessLogRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("Login grava usuario, IP, porta e a hora")
    void login() {
        service.recordLogin(userId, "177.10.0.1", 51000);

        AccessLog log = saved();
        assertThat(log.getId()).isNotNull();
        assertThat(log.getUserId()).isEqualTo(userId);
        assertThat(log.getEvent()).isEqualTo(AccessEvent.LOGIN);
        assertThat(log.getIp()).isEqualTo("177.10.0.1");
        assertThat(log.getPort()).isEqualTo(51000);
        assertThat(log.getCreatedAt().toInstant()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Refresh acha o dono pelo token novo; token sumido nao grava")
    void refresh() {
        BaseUser user = new BaseUser("a@b.com");
        user.setId(userId);
        RefreshToken token = mock(RefreshToken.class);
        when(token.getUser()).thenReturn(user);
        when(refreshTokenRepository.findByToken("novo")).thenReturn(Optional.of(token));

        service.recordRefresh("novo", "177.10.0.1", null);
        AccessLog log = saved();
        assertThat(log.getEvent()).isEqualTo(AccessEvent.REFRESH);
        assertThat(log.getPort()).isNull();

        when(refreshTokenRepository.findByToken("sumiu")).thenReturn(Optional.empty());
        service.recordRefresh("sumiu", "177.10.0.1", null);
        verify(accessLogRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("Sem usuario ou sem IP nao grava; IP longo e cortado em 45")
    void guards() {
        service.recordLogin(null, "1.2.3.4", 1);
        service.recordLogin(userId, null, 1);
        service.recordLogin(userId, " ", 1);
        verify(accessLogRepository, never()).save(any());

        service.recordLogin(userId, "x".repeat(60), null);
        assertThat(saved().getIp()).hasSize(45);
    }

    @Test
    @DisplayName("Falha ao gravar ou ao achar o token nao derruba o login")
    void bestEffort() {
        when(accessLogRepository.save(any())).thenThrow(new RuntimeException("banco fora"));
        assertThatCode(() -> service.recordLogin(userId, "1.2.3.4", 1)).doesNotThrowAnyException();

        when(refreshTokenRepository.findByToken(any())).thenThrow(new RuntimeException("banco fora"));
        assertThatCode(() -> service.recordRefresh("t", "1.2.3.4", 1)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Limpeza apaga o que tem mais de 6 meses")
    void purge() {
        when(accessLogRepository.deleteOlderThan(any())).thenReturn(3);

        assertThat(service.purgeExpired()).isEqualTo(3);

        ArgumentCaptor<OffsetDateTime> before = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(accessLogRepository).deleteOlderThan(before.capture());
        assertThat(before.getValue()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMonths(6));
    }

    @Test
    @DisplayName("O construtor do Spring usa o relogio do sistema")
    void defaultClock() {
        assertThatCode(() -> new AccessLogService(accessLogRepository, refreshTokenRepository).recordLogin(userId, "1.2.3.4", 1))
                .doesNotThrowAnyException();
    }
}
