package com.ospaulos.vupth.unit.user;

import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.api.user.dto.response.auth.RefreshTokenResponse;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosAndToken;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosResponse;
import com.ospaulos.vupth.api.user.mapper.UserMapper;
import com.ospaulos.vupth.api.user.usecase.auth.LoginUsecase;
import com.ospaulos.vupth.domain.refreshToken.model.RefreshToken;
import com.ospaulos.vupth.domain.user.exception.EmailNotRegisteredException;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import com.ospaulos.vupth.infrastructure.security.jwt.token.JwtTokenProvider;
import com.ospaulos.vupth.infrastructure.security.jwt.token.RefreshTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests — LoginUsecase
 *
 * Covers:
 * - Success: AuthenticationManager authenticates, access_token and refresh_token returned
 * - Bad credentials: AuthenticationManager throws BadCredentialsException, propagated
 * - Unknown e-mail: EmailNotRegisteredException before authenticating (BAK-6 · D2)
 *
 * The use case delegates credential validation entirely to Spring Security's
 * AuthenticationManager. The test verifies only the orchestration logic:
 * that tokens are requested from providers and the response is assembled correctly.
 *
 * @since v9
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LoginUsecase")
class LoginUseCaseTest {

    @Mock UserMapper             userMapper;
    @Mock JwtTokenProvider       providerToken;
    @Mock RefreshTokenProvider   refreshTokenProvider;
    @Mock AuthenticationManager  authenticationManager;
    @Mock UserRepository         userRepository;

    @InjectMocks LoginUsecase loginUsecase;

    private BaseUser       user;
    private UserDetailsImpl userDetails;

    @BeforeEach
    void setUp() {
        user = new BaseUser();
        user.setId(UUID.randomUUID());
        user.setEmail("user@example.com");
        user.setPassword("$2a$10$hashedPasswordMockForUnitTest12345");
        user.addClientProfile();

        userDetails = new UserDetailsImpl(user);
        lenient().when(userRepository.existsByEmail("user@example.com")).thenReturn(true);
    }

    @Test
    @DisplayName("success: access_token and refresh_token returned, user info included")
    void executeLogin_success() {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(userDetails);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(auth);

        when(providerToken.createToken(userDetails)).thenReturn("access-token-xyz");

        RefreshToken refreshToken = RefreshToken.builder()
                .id(UUID.randomUUID())
                .token("refresh-token-abc")
                .user(user)
                .expiryDate(OffsetDateTime.now().plusDays(7))
                .build();
        when(refreshTokenProvider.createRefreshToken(user)).thenReturn(refreshToken);

        UserProfileGeneralInfosResponse profile = mock(UserProfileGeneralInfosResponse.class);
        when(userMapper.toGeneralInfosUserResponse(user)).thenReturn(profile);

        UserProfileGeneralInfosAndToken result =
                loginUsecase.executeLogin(new LoginRequest("user@example.com", "secret"));

        assertThat(result).isNotNull();
        assertThat(result.tokens().accessToken()).isEqualTo("access-token-xyz");
        assertThat(result.tokens().refreshToken()).isEqualTo("refresh-token-abc");
        assertThat(result.user()).isEqualTo(profile);

        verify(providerToken).createToken(userDetails);
        verify(refreshTokenProvider).createRefreshToken(user);
        verify(userMapper).toGeneralInfosUserResponse(user);
    }

    @Test
    @DisplayName("bad credentials: AuthenticationManager throws, propagated before any token is created")
    void executeLogin_badCredentials_throws() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() ->
                loginUsecase.executeLogin(new LoginRequest("user@example.com", "wrong")))
                .isInstanceOf(BadCredentialsException.class);

        verify(providerToken, never()).createToken(any());
        verify(refreshTokenProvider, never()).createRefreshToken(any());
    }

    @Test
    @DisplayName("unknown e-mail: EmailNotRegisteredException, AuthenticationManager never called (BAK-6)")
    void executeLogin_unknownEmail_throws() {
        when(userRepository.existsByEmail("nobody@example.com")).thenReturn(false);

        assertThatThrownBy(() ->
                loginUsecase.executeLogin(new LoginRequest("nobody@example.com", "any")))
                .isInstanceOf(EmailNotRegisteredException.class);

        verifyNoInteractions(authenticationManager, providerToken, refreshTokenProvider);
    }
}
