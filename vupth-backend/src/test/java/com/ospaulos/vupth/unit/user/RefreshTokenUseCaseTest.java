package com.ospaulos.vupth.unit.user;

import com.ospaulos.vupth.api.user.dto.request.auth.RefreshTokenRequest;
import com.ospaulos.vupth.api.user.dto.response.auth.RefreshTokenResponse;
import com.ospaulos.vupth.api.user.usecase.auth.RefreshTokenUseCase;
import com.ospaulos.vupth.domain.refreshToken.exception.RefreshTokenInvalidException;
import com.ospaulos.vupth.domain.refreshToken.model.RefreshToken;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.security.jwt.token.JwtTokenProvider;
import com.ospaulos.vupth.infrastructure.security.jwt.token.RefreshTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests — RefreshTokenUseCase
 *
 * Covers:
 * - Success: old token revoked, new access_token and refresh_token generated
 * - Token not found: throws RefreshTokenInvalidException
 * - Expired token: verifyExpiration throws exception
 *
 * Business rule:
 * Refresh token is rotated on every use (single-use token rotation).
 * The old token is revoked immediately after a new one is issued.
 * Tokens older than 24h are cleaned up in batch (cleanupOldTokens).
 *
 * @since v6
 * @compat verifyExpiration returns RefreshToken (not void) — use when().thenReturn() not doNothing(). Fixed v9.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenUseCaseTest {

    @Mock RefreshTokenProvider refreshTokenProvider;
    @Mock JwtTokenProvider jwtTokenProvider;
    @Mock UserRepository userRepository;

    @InjectMocks RefreshTokenUseCase refreshTokenUseCase;

    private BaseUser user;
    private RefreshToken oldToken;
    private RefreshToken newToken;

    @BeforeEach
    void setUp() {
        user = new BaseUser();
        user.setId(UUID.randomUUID());
        user.setEmail("user@email.com");
        // lastTokenCleanupAt is null → cleanupOlderTokens will call userRepository.save

        oldToken = RefreshToken.builder()
                .id(UUID.randomUUID())
                .token("old-refresh-token")
                .user(user)
                .expiryDate(OffsetDateTime.now().plusDays(7))
                .build();

        newToken = RefreshToken.builder()
                .id(UUID.randomUUID())
                .token("new-refresh-token")
                .user(user)
                .expiryDate(OffsetDateTime.now().plusDays(7))
                .build();
    }

    @Test
    @DisplayName("execute — success: old token revoked, new tokens issued")
    void execute_success() {
        // verifyExpiration returns RefreshToken (not void) — cannot use doNothing()
        when(refreshTokenProvider.findByToken("old-refresh-token")).thenReturn(Optional.of(oldToken));
        when(refreshTokenProvider.verifyExpiration(oldToken)).thenReturn(oldToken);
        when(refreshTokenProvider.createRefreshToken(user)).thenReturn(newToken);
        when(jwtTokenProvider.createTokenFromUser(user)).thenReturn("new-access-token");
        // cleanupOlderTokens: lastTokenCleanupAt == null → refreshTokenProvider.cleanupOldTokens + userRepository.save
        doNothing().when(refreshTokenProvider).cleanupOldTokens(user);
        when(userRepository.save(user)).thenReturn(user);
        // revokeToken is void
        doNothing().when(refreshTokenProvider).revokeToken("old-refresh-token");

        RefreshTokenResponse result = refreshTokenUseCase.execute(
                new RefreshTokenRequest("old-refresh-token"));

        assertThat(result).isNotNull();
        assertThat(result.accessToken()).isEqualTo("new-access-token");
        assertThat(result.refreshToken()).isEqualTo("new-refresh-token");

        verify(refreshTokenProvider).verifyExpiration(oldToken);
        verify(refreshTokenProvider).revokeToken("old-refresh-token");
        verify(refreshTokenProvider).createRefreshToken(user);
        verify(jwtTokenProvider).createTokenFromUser(user);
    }

    @Test
    @DisplayName("execute — token not found: throws RefreshTokenInvalidException")
    void execute_tokenNotFound_throws() {
        when(refreshTokenProvider.findByToken("token-invalido")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                refreshTokenUseCase.execute(new RefreshTokenRequest("token-invalido")))
                .isInstanceOf(RefreshTokenInvalidException.class);

        verify(refreshTokenProvider, never()).createRefreshToken(any());
        verify(jwtTokenProvider, never()).createTokenFromUser(any());
    }

    @Test
    @DisplayName("execute — expired token: verifyExpiration throws RefreshTokenInvalidException")
    void execute_expiredToken_throws() {
        when(refreshTokenProvider.findByToken("expired-token")).thenReturn(Optional.of(oldToken));
        when(refreshTokenProvider.verifyExpiration(oldToken))
                .thenThrow(new RefreshTokenInvalidException());

        assertThatThrownBy(() ->
                refreshTokenUseCase.execute(new RefreshTokenRequest("expired-token")))
                .isInstanceOf(RefreshTokenInvalidException.class);

        verify(refreshTokenProvider, never()).revokeToken(any());
        verify(refreshTokenProvider, never()).createRefreshToken(any());
    }
}
