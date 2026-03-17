package com.ospaulos.vupth.domain.refreshToken.repository;

import com.ospaulos.vupth.domain.refreshToken.model.RefreshToken;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {

    RefreshToken saveRefreshToken(RefreshToken refreshToken);

    Optional<RefreshToken> findByToken(String token);

    void deleteByToken(RefreshToken token);

    void deleteOldRevokedTokens(UUID userId, OffsetDateTime limit);

    void deleteByUserId(UUID userId);

    int deleteExpiredAndRevokedTokens(OffsetDateTime now);

    long countActiveTokensByUserId(UUID userId, OffsetDateTime now);

    void revokeAllByUserId(UUID userId);

    int revokeByToken(String token);
}
