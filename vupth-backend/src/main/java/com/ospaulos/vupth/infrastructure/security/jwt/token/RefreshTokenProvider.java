package com.ospaulos.vupth.infrastructure.security.jwt.token;

import com.ospaulos.vupth.domain.refreshToken.exception.RefreshTokenInvalidException;
import com.ospaulos.vupth.domain.refreshToken.model.RefreshToken;
import com.ospaulos.vupth.domain.refreshToken.repository.RefreshTokenRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenProvider {

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${jwt.refresh-token.expiration:P7D}")
    private Duration expirationTime;

    public RefreshToken createRefreshToken(BaseUser user){

        refreshTokenRepository.deleteByUserId(user.getId());

        String secureToken = generateSecureToken();

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .token(secureToken)
                .expiryDate(OffsetDateTime.now().plus(expirationTime))
                .createdAt(OffsetDateTime.now())
                .build();

        return refreshTokenRepository.saveRefreshToken(refreshToken);
    }

    public Optional<RefreshToken> findByToken(String refreshToken) {
        return refreshTokenRepository.findByToken(refreshToken);
    }

    public RefreshToken verifyExpiration(RefreshToken refreshToken) {
        if (!refreshToken.isValid()) {
            refreshTokenRepository.deleteByToken(refreshToken);
            throw new RefreshTokenInvalidException();
        }
        return refreshToken;
    }

    private String generateSecureToken(){
        SecureRandom secureRandom = new SecureRandom();
        byte[] randomBytes = new byte[32]; // 256 bits
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

    }

    @Transactional
    public void revokeToken(String refreshToken) {

        int updated = refreshTokenRepository.revokeByToken(refreshToken);

        if(updated == 0) {
            log.warn("Refresh token already revoked or not found");
        }
    }

    @Transactional
    public void revokeAllUserTokens(UUID userId) {
        refreshTokenRepository.deleteByUserId(userId);
    }

    @Transactional
    public void cleanupOldTokens(BaseUser user) {

        refreshTokenRepository.deleteOldRevokedTokens(
                user.getId(),
                OffsetDateTime.now().minusDays(14)
        );
    }
}
