package com.ospaulos.vupth.domain.access.service;

import com.ospaulos.vupth.domain.access.model.AccessEvent;
import com.ospaulos.vupth.domain.access.model.AccessLog;
import com.ospaulos.vupth.domain.access.repository.AccessLogRepository;
import com.ospaulos.vupth.domain.refreshToken.repository.RefreshTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Grava o registro de acesso (Marco Civil, art. 15; registro-de-acesso, 10/10) e apaga o que passou de
 * {@value #RETENTION_MONTHS} meses. Best-effort: falhar ao gravar nao pode derrubar o login.
 */
@Slf4j
@Service
public class AccessLogService {

    /** Prazo de guarda do Marco Civil para o provedor de aplicacao. */
    public static final int RETENTION_MONTHS = 6;

    private final AccessLogRepository accessLogRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    @Autowired
    public AccessLogService(AccessLogRepository accessLogRepository, RefreshTokenRepository refreshTokenRepository) {
        this(accessLogRepository, refreshTokenRepository, Clock.systemUTC());
    }

    public AccessLogService(AccessLogRepository accessLogRepository, RefreshTokenRepository refreshTokenRepository,
                            Clock clock) {
        this.accessLogRepository = accessLogRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
    }

    public void recordLogin(UUID userId, String ip, Integer port) {
        record(userId, AccessEvent.LOGIN, ip, port);
    }

    /** O refresh devolve so os tokens: o dono vem do refresh token novo. */
    public void recordRefresh(String newRefreshToken, String ip, Integer port) {
        try {
            refreshTokenRepository.findByToken(newRefreshToken)
                    .ifPresent(t -> record(t.getUser().getId(), AccessEvent.REFRESH, ip, port));
        } catch (Exception e) {
            log.warn("[Access] falha ao achar o dono do refresh para o registro de acesso: {}", e.getMessage());
        }
    }

    private void record(UUID userId, AccessEvent event, String ip, Integer port) {
        if (userId == null || ip == null || ip.isBlank()) {
            return;
        }
        try {
            String cleanIp = ip.length() > 45 ? ip.substring(0, 45) : ip;
            accessLogRepository.save(new AccessLog(userId, event, cleanIp, port, OffsetDateTime.now(clock)));
        } catch (Exception e) {
            log.warn("[Access] falha ao gravar o registro de acesso de {} ({}): {}", userId, event, e.getMessage());
        }
    }

    /** Apaga o que passou do prazo de guarda. Devolve quantos sairam. */
    public int purgeExpired() {
        int removed = accessLogRepository.deleteOlderThan(OffsetDateTime.now(clock).minusMonths(RETENTION_MONTHS));
        log.info("[Access] limpeza — {} registro(s) de acesso com mais de {} meses apagado(s)", removed, RETENTION_MONTHS);
        return removed;
    }
}
