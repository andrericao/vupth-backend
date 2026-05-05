package com.ospaulos.vupth.unit.user;

import com.ospaulos.vupth.api.user.dto.request.profile.EmailRequest;
import com.ospaulos.vupth.api.user.dto.request.profile.PasswordRequest;
import com.ospaulos.vupth.api.user.dto.response.auth.RefreshTokenResponse;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosAndToken;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosResponse;
import com.ospaulos.vupth.api.user.exception.EmailsAreTheSameException;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.api.user.mapper.UserMapper;
import com.ospaulos.vupth.api.user.usecase.profile.UpdateEmailUseCase;
import com.ospaulos.vupth.api.user.usecase.profile.UpdatePasswordUseCase;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.service.refreshToken.UtilsTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests — UpdateEmailUseCase and UpdatePasswordUseCase
 *
 * Cobre:
 *
 * UpdateEmailUseCase:
 * - Successful update: new email saved, tokens rotated
 * - Same email: throws EmailsAreTheSameException
 * - User not found: throws UserNotFoundException
 *
 * UpdatePasswordUseCase:
 * - Successful update: password encoded, tokens rotated
 * - User not found: throws UserNotFoundException
 *
 * Regra documentada:
 * Every email or password change invalidates existing tokens (rotation via UtilsTokens).
 * This forces re-login on devices that did not perform the change.
 */
@ExtendWith(MockitoExtension.class)
class UpdateProfileUseCasesTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String CURRENT_EMAIL = "atual@email.com";
    private static final String NEW_EMAIL = "novo@email.com";

    private BaseUser user;
    private RefreshTokenResponse fakeTokens;
    private UserProfileGeneralInfosResponse generalInfos;

    @BeforeEach
    void setUp() {
        user = new BaseUser();
        user.setId(USER_ID);
        user.setEmail(CURRENT_EMAIL);
        user.setPassword("$2a$hashed");

        fakeTokens = new RefreshTokenResponse("new_access", "new_refresh", 3600L);
        generalInfos = new UserProfileGeneralInfosResponse(
                USER_ID, null, null, NEW_EMAIL, null, null, null, null, null, null,
                Collections.emptyList());
    }

    // ─────────────────────────────────────────────
    // UpdateEmailUseCase
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("UpdateEmailUseCase")
    class UpdateEmailTests {

        @Mock UserRepository repository;
        @Mock UserMapper mapper;
        @Mock UtilsTokens utilsTokens;

        @InjectMocks UpdateEmailUseCase updateEmailUseCase;

        @Test
        @DisplayName("executeUpdateEmail — sucesso: email atualizado, tokens rotacionados")
        void updateEmail_success() {
            when(repository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(repository.save(any())).thenReturn(user);
            when(utilsTokens.rotateTokens(any())).thenReturn(fakeTokens);
            when(mapper.toGeneralInfosUserResponse(any())).thenReturn(generalInfos);

            UserProfileGeneralInfosAndToken result =
                    updateEmailUseCase.executeUpdateEmail(USER_ID, new EmailRequest(NEW_EMAIL));

            assertThat(result).isNotNull();
            assertThat(result.tokens()).isEqualTo(fakeTokens);
            // Email deve ter sido atualizado no objeto antes de salvar
            assertThat(user.getEmail()).isEqualTo(NEW_EMAIL);
            verify(repository).save(user);
            verify(utilsTokens).rotateTokens(user);
        }

        @Test
        @DisplayName("executeUpdateEmail — throws EmailsAreTheSameException if email is same as current")
        void updateEmail_sameEmail_throws() {
            when(repository.findById(USER_ID)).thenReturn(Optional.of(user));

            assertThatThrownBy(() ->
                    updateEmailUseCase.executeUpdateEmail(USER_ID, new EmailRequest(CURRENT_EMAIL)))
                    .isInstanceOf(EmailsAreTheSameException.class);

            verify(repository, never()).save(any());
            verify(utilsTokens, never()).rotateTokens(any());
        }

        @Test
        @DisplayName("executeUpdateEmail — throws UserNotFoundException if user does not exist")
        void updateEmail_userNotFound_throws() {
            when(repository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    updateEmailUseCase.executeUpdateEmail(USER_ID, new EmailRequest(NEW_EMAIL)))
                    .isInstanceOf(UserNotFoundException.class);
        }
    }

    // ─────────────────────────────────────────────
    // UpdatePasswordUseCase
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("UpdatePasswordUseCase")
    class UpdatePasswordTests {

        @Mock UserRepository repository;
        @Mock PasswordEncoder passwordEncoder;
        @Mock UtilsTokens utilsTokens;
        @Mock UserMapper mapper;

        @InjectMocks UpdatePasswordUseCase updatePasswordUseCase;

        @Test
        @DisplayName("executeUpdatePassword — sucesso: senha encodada, tokens rotacionados")
        void updatePassword_success() {
            when(repository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(passwordEncoder.encode("novaSenha123")).thenReturn("$2a$novo_hash");
            when(repository.save(any())).thenReturn(user);
            when(utilsTokens.rotateTokens(any())).thenReturn(fakeTokens);
            when(mapper.toGeneralInfosUserResponse(any())).thenReturn(generalInfos);

            UserProfileGeneralInfosAndToken result =
                    updatePasswordUseCase.executeUpdatePassword(USER_ID, new PasswordRequest("novaSenha123"));

            assertThat(result).isNotNull();
            assertThat(user.getPassword()).isEqualTo("$2a$novo_hash");
            verify(passwordEncoder).encode("novaSenha123");
            verify(utilsTokens).rotateTokens(user);
        }

        @Test
        @DisplayName("executeUpdatePassword — throws UserNotFoundException if user does not exist")
        void updatePassword_userNotFound_throws() {
            when(repository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    updatePasswordUseCase.executeUpdatePassword(USER_ID, new PasswordRequest("qualquer")))
                    .isInstanceOf(UserNotFoundException.class);
        }

        @Test
        @DisplayName("executeUpdatePassword — old password is never kept in the saved object")
        void updatePassword_oldPasswordNotPersisted() {
            String oldHash = user.getPassword();
            when(repository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$novo_hash");
            when(repository.save(any())).thenReturn(user);
            when(utilsTokens.rotateTokens(any())).thenReturn(fakeTokens);
            when(mapper.toGeneralInfosUserResponse(any())).thenReturn(generalInfos);

            updatePasswordUseCase.executeUpdatePassword(USER_ID, new PasswordRequest("outraSenha"));

            assertThat(user.getPassword()).isNotEqualTo(oldHash);
        }
    }
}
