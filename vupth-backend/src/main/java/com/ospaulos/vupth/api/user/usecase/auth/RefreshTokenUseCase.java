package com.ospaulos.vupth.api.user.usecase.auth;

import com.ospaulos.vupth.api.user.dto.request.auth.RefreshTokenRequest;
import com.ospaulos.vupth.api.user.dto.response.auth.RefreshTokenResponse;
import com.ospaulos.vupth.domain.refreshToken.exception.RefreshTokenInvalidException;
import com.ospaulos.vupth.domain.refreshToken.model.RefreshToken;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.security.jwt.token.JwtTokenProvider;
import com.ospaulos.vupth.infrastructure.security.jwt.token.RefreshTokenProvider;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class RefreshTokenUseCase {

    private final RefreshTokenProvider refreshTokenProvider;
    private final JwtTokenProvider jwtTokenProvider;

    private final UserRepository userRepository;

    @Transactional
    public RefreshTokenResponse execute(RefreshTokenRequest request){

        RefreshToken oldToken = refreshTokenProvider
                .findByToken(request.refreshToken())
                .orElseThrow(RefreshTokenInvalidException::new);

        refreshTokenProvider.verifyExpiration(oldToken);

        BaseUser user = oldToken.getUser();

        cleanupOlderTokens(user);

        refreshTokenProvider.revokeToken(oldToken.getToken());

        RefreshToken newRefreshToken = refreshTokenProvider.createRefreshToken(user);

        String newAccessToken = jwtTokenProvider.createTokenFromUser(user);

        Long refreshExpiresIn = Duration.between(
                OffsetDateTime.now(), oldToken.getExpiryDate()
        ).getSeconds();

        return new RefreshTokenResponse(
                newAccessToken,
                newRefreshToken.getToken(),
                refreshExpiresIn
        );
    }

    public void cleanupOlderTokens(BaseUser user) {

        OffsetDateTime now = OffsetDateTime.now();

        if (user.getLastTokenCleanupAt() == null ||
                user.getLastTokenCleanupAt().isBefore(now.minusHours(24))) {

            refreshTokenProvider.cleanupOldTokens(user);

            user.setLastTokenCleanupAt(now);
            userRepository.save(user);
        }
    }
}