package com.ospaulos.vupth.infrastructure.service.refreshToken;

import com.ospaulos.vupth.api.user.dto.response.auth.RefreshTokenResponse;
import com.ospaulos.vupth.domain.refreshToken.model.RefreshToken;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.infrastructure.security.jwt.token.JwtTokenProvider;
import com.ospaulos.vupth.infrastructure.security.jwt.token.RefreshTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class UtilsTokens {

    private final RefreshTokenProvider refreshTokenProvider;
    private final JwtTokenProvider jwtTokenProvider;

    public RefreshTokenResponse rotateTokens(BaseUser user){

        refreshTokenProvider.revokeAllUserTokens(user.getId());

        RefreshToken refreshToken = refreshTokenProvider.createRefreshToken(user);
        String accessToken = jwtTokenProvider.createTokenFromUser(user);

        Long refreshTokenExpiresIn = Duration.between(
                OffsetDateTime.now(),
                refreshToken.getExpiryDate()
        ).getSeconds();

        return new RefreshTokenResponse(
                accessToken,
                refreshToken.getToken(),
                refreshTokenExpiresIn
        );
    }
}
