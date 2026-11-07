package com.ospaulos.vupth.domain.admin.service;

import com.ospaulos.vupth.domain.admin.exception.AdminTotpException;
import com.ospaulos.vupth.domain.admin.model.AdminSession;
import com.ospaulos.vupth.domain.admin.repository.AdminSessionRepository;
import com.ospaulos.vupth.domain.setting.AppSettingService;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import com.ospaulos.vupth.infrastructure.security.jwt.token.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * sessao-configuravel-do-painel (DEC-187): o token do painel segue curto (15 min) e quem entrou o renova sem senha nem codigo. A
 * duracao escolhida conta como <b>tempo parado</b> e vale para todos os usuarios do painel: cada renovacao zera a conta, e a sessao
 * acaba quando passa o tempo sem uso ou no teto de {@value #MAX_DAYS} dias desde a entrada.
 *
 * <p>O refresh troca a cada uso. O anterior ainda vale por {@link #GRACE} (duas abas renovando juntas): devolve so o token de acesso.
 * Depois disso, apresentar o anterior e sinal de roubo e encerra a sessao.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSessionService {

    public static final int DEFAULT_IDLE_MINUTES = 480;
    /** 1 h, 8 h, 24 h, 7 d e 30 d. */
    public static final List<Integer> IDLE_OPTIONS = List.of(60, 480, 1440, 10080, 43200);
    public static final int MAX_DAYS = 30;
    static final Duration GRACE = Duration.ofSeconds(30);
    private static final String IDLE_SETTING = "admin.session.idle_minutes";

    private final AdminSessionRepository sessions;
    private final AppSettingService settings;
    private final UserRepository users;
    private final JwtTokenProvider tokens;
    private final SecureRandom random = new SecureRandom();

    public record Opened(String refreshToken, long expiresIn) {}

    /** {@code refreshToken} nulo: renovou pela folga do token anterior, e o novo ja esta com quem renovou primeiro. */
    public record Renewed(String accessToken, String refreshToken, long expiresIn) {}

    @Transactional(readOnly = true)
    public int idleMinutes() {
        return settings.decimal(IDLE_SETTING, BigDecimal.valueOf(DEFAULT_IDLE_MINUTES)).intValue();
    }

    @Transactional
    public void changeIdleMinutes(int minutes, UUID by) {
        if (!IDLE_OPTIONS.contains(minutes)) {
            throw new AdminTotpException(HttpStatus.BAD_REQUEST, "ADMIN_SESSION_DURATION_INVALID",
                    "Escolha uma das duracoes da lista.");
        }
        settings.setDecimal(IDLE_SETTING, BigDecimal.valueOf(minutes), by);
    }

    @Transactional
    public Opened open(UUID userId) {
        OffsetDateTime now = OffsetDateTime.now();
        sessions.deleteStartedBefore(userId, now.minusDays(MAX_DAYS));
        String raw = newToken();
        AdminSession session = new AdminSession();
        session.setId(UUID.randomUUID());
        session.setUserId(userId);
        session.setTokenHash(hash(raw));
        session.setIdleMinutes(idleMinutes());
        session.setStartedAt(now);
        session.setLastUsedAt(now);
        sessions.save(session);
        return new Opened(raw, secondsLeft(session, now));
    }

    /** Sem senha e sem codigo: troca o refresh por um token do painel novo. Falhou: 401 e o painel pede o codigo de novo. */
    @Transactional(noRollbackFor = AdminTotpException.class)
    public Renewed refresh(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid();
        }
        String presented = hash(raw);
        AdminSession session = sessions.findForUpdate(presented).orElseThrow(AdminSessionService::invalid);
        OffsetDateTime now = OffsetDateTime.now();
        if (session.getRevokedAt() != null) {
            throw expired();
        }
        boolean previous = !presented.equals(session.getTokenHash());
        if (previous && session.getRotatedAt().plus(GRACE).isBefore(now)) {
            log.warn("[AdminSession] refresh antigo reapresentado, sessao encerrada user={}", session.getUserId());
            revoke(session, now);
            throw invalid();
        }
        if (secondsLeft(session, now) <= 0) {
            revoke(session, now);
            throw expired();
        }
        UserDetailsImpl admin = adminOf(session);
        if (admin == null) {
            revoke(session, now);
            throw expired();
        }
        String next = null;
        if (!previous) {
            next = newToken();
            session.setPreviousHash(presented);
            session.setTokenHash(hash(next));
            session.setRotatedAt(now);
        }
        session.setLastUsedAt(now);
        sessions.save(session);
        return new Renewed(tokens.createAdminToken(admin), next, secondsLeft(session, now));
    }

    /** Sair do painel. Token desconhecido nao e erro: quem saiu ja esta fora. */
    @Transactional
    public void close(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        sessions.findForUpdate(hash(raw)).filter(s -> s.getRevokedAt() == null)
                .ifPresent(s -> revoke(s, OffsetDateTime.now()));
    }

    /** Troca de senha ou de e-mail: toda sessao do painel dessa conta cai. */
    @Transactional
    public void closeAll(UUID userId) {
        sessions.revokeAll(userId, OffsetDateTime.now());
    }

    /** A conta ainda e do painel? Desativada ou sem o papel: a sessao acaba. */
    private UserDetailsImpl adminOf(AdminSession session) {
        BaseUser user = users.findById(session.getUserId()).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getIsActive())) {
            return null;
        }
        UserDetailsImpl details = new UserDetailsImpl(user);
        boolean admin = details.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return admin ? details : null;
    }

    private void revoke(AdminSession session, OffsetDateTime now) {
        session.setRevokedAt(now);
        sessions.save(session);
    }

    /** O menor entre o tempo parado que falta e o teto. */
    private static long secondsLeft(AdminSession session, OffsetDateTime now) {
        OffsetDateTime idleEnd = session.getLastUsedAt().plusMinutes(session.getIdleMinutes());
        OffsetDateTime hardEnd = session.getStartedAt().plusDays(MAX_DAYS);
        return Duration.between(now, idleEnd.isBefore(hardEnd) ? idleEnd : hardEnd).getSeconds();
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AdminTotpException invalid() {
        return new AdminTotpException(HttpStatus.UNAUTHORIZED, "ADMIN_SESSION_INVALID", "Sessao do painel invalida.");
    }

    private static AdminTotpException expired() {
        return new AdminTotpException(HttpStatus.UNAUTHORIZED, "ADMIN_SESSION_EXPIRED", "A sessao do painel terminou.");
    }
}
