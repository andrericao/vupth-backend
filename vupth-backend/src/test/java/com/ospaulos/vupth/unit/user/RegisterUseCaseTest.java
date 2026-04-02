package com.ospaulos.vupth.unit.user;

import com.ospaulos.vupth.api.user.dto.request.profile.UserRegisterRequest;
import com.ospaulos.vupth.api.user.dto.response.UserRoleResponse;
import com.ospaulos.vupth.api.user.dto.response.profile.UserRegisterResponse;
import com.ospaulos.vupth.api.user.exception.EmailNotAvailableException;
import com.ospaulos.vupth.api.user.mapper.UserMapper;
import com.ospaulos.vupth.api.user.specification.EmailAvailabilitySpecification;
import com.ospaulos.vupth.api.user.usecase.profile.RegisterUseCase;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests — RegisterUseCase
 *
 * Covers:
 * - Success: user saved with CLIENT role automatically assigned
 * - Duplicate email → throws EmailNotAvailableException
 * - Password is BCrypt-encoded before saving
 *
 * Business rules:
 * - Every registered user automatically receives the CLIENT role (addClientProfile).
 * - Password is encoded; the plain-text value must never be stored.
 *
 * Wiring note:
 * RegisterUseCase has four dependencies via @AllArgsConstructor:
 *   UserMapper, UserRepository, PasswordEncoder, EmailAvailabilitySpecification.
 * All four must be declared as @Mock so @InjectMocks can wire them.
 * Stubbing emailAvailabilitySpecification.isAvaliable() (not userRepository.existsByEmail)
 * because the use case delegates the check to the specification object.
 *
 * @since v7
 * @compat roles is Set<UserRoleResponse> — extract roleType() when asserting. Fixed v8.
 * @compat Added @Mock UserMapper and @Mock EmailAvailabilitySpecification. Fixed v9.
 */
@ExtendWith(MockitoExtension.class)
class RegisterUseCaseTest {

    @Mock UserMapper userMapper;
    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock EmailAvailabilitySpecification emailAvailabilitySpecification;

    @InjectMocks RegisterUseCase registerUseCase;

    private String email;
    private String rawPassword;

    @BeforeEach
    void setUp() {
        email = "user@example.com";
        rawPassword = "secret123";
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns a saved BaseUser with CLIENT role already added (simulates what addClientProfile does).
     * The UserMapper.toRegisterResponse stub maps this to a UserRegisterResponse with that role.
     */
    private BaseUser savedUserWithClientRole() {
        BaseUser u = new BaseUser();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setPassword("$2a$encoded");
        u.addClientProfile();
        return u;
    }

    /** Minimal UserRegisterResponse matching a saved user with CLIENT role. */
    private UserRegisterResponse registerResponseFor(BaseUser u) {
        return new UserRegisterResponse(
                u.getId(),
                u.getEmail(),
                OffsetDateTime.now(),
                Set.of(new UserRoleResponse(RoleType.CLIENT, true))
        );
    }

    // ─────────────────────────────────────────────────────────────
    // Tests
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("success: user saved with CLIENT role automatically assigned")
    void register_success_clientRoleAdded() {
        // emailAvailabilitySpecification delegates to userRepository internally,
        // but in unit tests we stub the specification directly.
        when(emailAvailabilitySpecification.isAvaliable(email)).thenReturn(true);
        when(passwordEncoder.encode(rawPassword)).thenReturn("$2a$encoded");

        BaseUser newUser = new BaseUser();
        newUser.setEmail(email);
        when(userMapper.toUserEntity(any(UserRegisterRequest.class))).thenReturn(newUser);

        when(userRepository.save(any(BaseUser.class))).thenAnswer(inv -> {
            BaseUser u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        BaseUser savedRef = new BaseUser();
        savedRef.setId(UUID.randomUUID());
        savedRef.setEmail(email);
        savedRef.addClientProfile();

        when(userMapper.toRegisterResponse(any(BaseUser.class))).thenAnswer(inv ->
                registerResponseFor(inv.getArgument(0)));

        UserRegisterResponse response = registerUseCase.executeRegister(
                new UserRegisterRequest(email, rawPassword));

        assertThat(response).isNotNull();
        assertThat(response.email()).isEqualTo(email);
        assertThat(response.roles())
                .extracting(UserRoleResponse::roleType)
                .contains(RoleType.CLIENT);

        verify(userRepository).save(argThat(BaseUser::isClient));
    }

    @Test
    @DisplayName("duplicate email → throws EmailNotAvailableException")
    void register_duplicateEmail_throws() {
        when(emailAvailabilitySpecification.isAvaliable(email)).thenReturn(false);

        assertThatThrownBy(() ->
                registerUseCase.executeRegister(new UserRegisterRequest(email, rawPassword)))
                .isInstanceOf(EmailNotAvailableException.class)
                .hasMessageContaining(email);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("password is BCrypt-encoded before saving")
    void register_passwordEncoded() {
        when(emailAvailabilitySpecification.isAvaliable(email)).thenReturn(true);
        when(passwordEncoder.encode(rawPassword)).thenReturn("$2a$encodedValue");

        BaseUser newUser = new BaseUser();
        newUser.setEmail(email);
        when(userMapper.toUserEntity(any(UserRegisterRequest.class))).thenReturn(newUser);

        when(userRepository.save(any())).thenAnswer(inv -> {
            BaseUser u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        when(userMapper.toRegisterResponse(any(BaseUser.class))).thenAnswer(inv ->
                registerResponseFor(inv.getArgument(0)));

        registerUseCase.executeRegister(new UserRegisterRequest(email, rawPassword));

        verify(userRepository).save(argThat(u ->
                u.getPassword().equals("$2a$encodedValue") &&
                !u.getPassword().equals(rawPassword)));
    }
}
