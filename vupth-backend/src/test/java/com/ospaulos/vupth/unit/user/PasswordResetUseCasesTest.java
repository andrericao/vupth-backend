package com.ospaulos.vupth.unit.user;

import com.ospaulos.vupth.api.user.dto.request.auth.ForgotPasswordRequest;
import com.ospaulos.vupth.api.user.dto.request.auth.ResetPasswordRequest;
import com.ospaulos.vupth.api.user.exception.InvalidResetCodeException;
import com.ospaulos.vupth.api.user.exception.ResetCodeExpiredException;
import com.ospaulos.vupth.api.user.usecase.auth.ForgotPasswordUseCase;
import com.ospaulos.vupth.api.user.usecase.auth.ResetPasswordUseCase;
import com.ospaulos.vupth.domain.user.exception.EmailNotRegisteredException;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.PasswordResetCode;
import com.ospaulos.vupth.domain.user.repository.PasswordResetCodeRepository;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests — ForgotPasswordUseCase e ResetPasswordUseCase (backlog #1).
 *
 * Cobre o que o teste de integração não alcança sem relógio real:
 * ForgotPasswordUseCase:
 * - e-mail inexistente: não gera código nem envia (anti-enumeração)
 * - e-mail existente: apaga códigos antigos, salva hash e dispara e-mail
 *
 * ResetPasswordUseCase:
 * - sucesso: senha encodada + código consumido
 * - código errado: incrementa attempts e lança InvalidResetCodeException (senha intacta)
 * - última tentativa: ao estourar MAX_ATTEMPTS o código é consumido (queimado)
 * - código expirado: consumido + ResetCodeExpiredException
 * - código já consumido: InvalidResetCodeException
 * - usuário inexistente no reset: InvalidResetCodeException (sem vazar existência)
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetUseCasesTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String EMAIL = "reset@vupth.test";

    @Mock UserRepository userRepository;
    @Mock PasswordResetCodeRepository resetCodeRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock EmailSender emailSender;

    @InjectMocks ForgotPasswordUseCase forgotPasswordUseCase;
    @InjectMocks ResetPasswordUseCase resetPasswordUseCase;

    private BaseUser user;

    @BeforeEach
    void setUp() {
        user = new BaseUser();
        user.setId(USER_ID);
        user.setEmail(EMAIL);
        user.setPassword("$2a$hash_antigo");
    }

    @Nested
    class Forgot {

        @Test
        @DisplayName("e-mail inexistente — EmailNotRegisteredException, sem código nem envio (D2)")
        void unknownEmail_throws() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> forgotPasswordUseCase.execute(new ForgotPasswordRequest(EMAIL)))
                    .isInstanceOf(EmailNotRegisteredException.class);

            verify(resetCodeRepository, never()).save(any());
            verifyNoInteractions(emailSender);
        }

        @Test
        @DisplayName("e-mail existente — apaga anteriores, salva hash e dispara e-mail")
        void knownEmail_savesAndSends() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$hash_do_codigo");

            forgotPasswordUseCase.execute(new ForgotPasswordRequest(EMAIL));

            verify(resetCodeRepository).deleteByUserId(USER_ID);
            verify(resetCodeRepository).save(any(PasswordResetCode.class));
            verify(emailSender).sendPasswordResetCode(eq(EMAIL), matches("\\d{6}"));
        }
    }

    @Nested
    class Reset {

        private PasswordResetCode freshCode() {
            PasswordResetCode c = new PasswordResetCode(
                    USER_ID, "$2a$hash_do_codigo", OffsetDateTime.now().plusMinutes(15));
            return c;
        }

        @Test
        @DisplayName("sucesso — senha encodada e código consumido")
        void success() {
            PasswordResetCode code = freshCode();
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
            when(resetCodeRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(Optional.of(code));
            when(passwordEncoder.matches("123456", code.getCodeHash())).thenReturn(true);
            when(passwordEncoder.encode("NovaSenha@1")).thenReturn("$2a$hash_novo");

            resetPasswordUseCase.execute(new ResetPasswordRequest(EMAIL, "123456", "NovaSenha@1"));

            assertThat(user.getPassword()).isEqualTo("$2a$hash_novo");
            assertThat(code.isConsumed()).isTrue();
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("código errado — incrementa attempts e lança InvalidResetCode (senha intacta)")
        void wrongCode_incrementsAndThrows() {
            PasswordResetCode code = freshCode();
            String oldHash = user.getPassword();
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
            when(resetCodeRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(Optional.of(code));
            when(passwordEncoder.matches("999999", code.getCodeHash())).thenReturn(false);

            assertThatThrownBy(() ->
                    resetPasswordUseCase.execute(new ResetPasswordRequest(EMAIL, "999999", "NovaSenha@1")))
                    .isInstanceOf(InvalidResetCodeException.class);

            assertThat(code.getAttempts()).isEqualTo(1);
            assertThat(code.isConsumed()).isFalse();
            assertThat(user.getPassword()).isEqualTo(oldHash);
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("última tentativa errada — ao estourar MAX_ATTEMPTS o código é queimado")
        void lastAttempt_burnsCode() {
            PasswordResetCode code = freshCode();
            code.setAttempts(PasswordResetCode.MAX_ATTEMPTS - 1);
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
            when(resetCodeRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(Optional.of(code));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

            assertThatThrownBy(() ->
                    resetPasswordUseCase.execute(new ResetPasswordRequest(EMAIL, "999999", "NovaSenha@1")))
                    .isInstanceOf(InvalidResetCodeException.class);

            assertThat(code.getAttempts()).isEqualTo(PasswordResetCode.MAX_ATTEMPTS);
            assertThat(code.isConsumed()).isTrue();
        }

        @Test
        @DisplayName("código expirado — consumido + ResetCodeExpiredException")
        void expired_throws() {
            PasswordResetCode code = new PasswordResetCode(
                    USER_ID, "$2a$hash_do_codigo", OffsetDateTime.now().minusMinutes(1));
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
            when(resetCodeRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(Optional.of(code));

            assertThatThrownBy(() ->
                    resetPasswordUseCase.execute(new ResetPasswordRequest(EMAIL, "123456", "NovaSenha@1")))
                    .isInstanceOf(ResetCodeExpiredException.class);

            assertThat(code.isConsumed()).isTrue();
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("código já consumido — InvalidResetCodeException")
        void consumed_throws() {
            PasswordResetCode code = freshCode();
            code.setConsumed(true);
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
            when(resetCodeRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(Optional.of(code));

            assertThatThrownBy(() ->
                    resetPasswordUseCase.execute(new ResetPasswordRequest(EMAIL, "123456", "NovaSenha@1")))
                    .isInstanceOf(InvalidResetCodeException.class);

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("usuário inexistente no reset — InvalidResetCodeException (não vaza existência)")
        void unknownUser_throws() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    resetPasswordUseCase.execute(new ResetPasswordRequest(EMAIL, "123456", "NovaSenha@1")))
                    .isInstanceOf(InvalidResetCodeException.class);
        }
    }
}
