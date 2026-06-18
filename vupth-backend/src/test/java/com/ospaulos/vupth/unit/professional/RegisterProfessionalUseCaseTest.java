package com.ospaulos.vupth.unit.professional;

import com.ospaulos.vupth.api.professional.dto.request.ProfessionalRegisterRequest;
import com.ospaulos.vupth.api.professional.dto.response.ProfessionalQuickCreateResponse;
import com.ospaulos.vupth.api.professional.exception.UserAlreadyProfessionalException;
import com.ospaulos.vupth.api.professional.usecase.RegisterProfessionalUseCase;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.domain.professional.service.impl.ProfessionalIntervalService;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests — RegisterProfessionalUseCase
 *
 * Business rules:
 *  - User must exist.
 *  - User must NOT already have PROFESSIONAL role.
 *  - Professional is created with establishment = null.
 *  - firstName is set from request if blank.
 *  - Response has null establishmentId and establishmentName.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RegisterProfessionalUseCase")
class RegisterProfessionalUseCaseTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock UserRepository userRepository;
    @Mock ProfessionalIntervalService intervalService;
    @InjectMocks RegisterProfessionalUseCase useCase;

    private BaseUser user;

    @BeforeEach
    void setUp() {
        user = new BaseUser();
        user.setId(USER_ID);
        user.setEmail("prof@test.com");
        user.setPassword("$2a$hash");
        user.addClientProfile();
    }

    // ─────────────────────────────────────────────────────────────
    // Happy path
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("success cases")
    class SuccessCases {

        @Test
        @DisplayName("success: PROFESSIONAL role added, establishment is null")
        void execute_success_noProfessionalRoleYet() {
            assertThat(user.isProfessional()).isFalse();

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenReturn(user);

            ProfessionalQuickCreateResponse result =
                    useCase.execute(USER_ID, new ProfessionalRegisterRequest("Carlos", "Barber"));

            assertThat(result).isNotNull();
            assertThat(user.isProfessional()).isTrue();
            assertThat(user.isClient()).isTrue(); // CLIENT preserved
            assertThat(result.establishmentId()).isNull();
            assertThat(result.establishmentName()).isNull();
            verify(userRepository).save(any(BaseUser.class));
        }

        @Test
        @DisplayName("success: specialty is set correctly")
        void execute_success_specialtySet() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenReturn(user);

            ProfessionalQuickCreateResponse result =
                    useCase.execute(USER_ID, new ProfessionalRegisterRequest("Carlos", "Corte Masculino"));

            assertThat(result.specialty()).isEqualTo("Corte Masculino");
        }

        @Test
        @DisplayName("success: specialty can be null (optional)")
        void execute_success_nullSpecialty() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenReturn(user);

            ProfessionalQuickCreateResponse result =
                    useCase.execute(USER_ID, new ProfessionalRegisterRequest("Carlos", null));

            assertThat(result).isNotNull();
            assertThat(user.isProfessional()).isTrue();
        }

        @Test
        @DisplayName("blank firstName is filled from request name")
        void execute_setsFirstNameWhenBlank() {
            user.setFirstName(null);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenReturn(user);

            useCase.execute(USER_ID, new ProfessionalRegisterRequest("Carlos Silva", null));

            assertThat(user.getFirstName()).isEqualTo("Carlos Silva");
        }

        @Test
        @DisplayName("existing firstName is preserved (not overwritten)")
        void execute_doesNotOverwriteExistingName() {
            user.setFirstName("Existing Name");

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenReturn(user);

            useCase.execute(USER_ID, new ProfessionalRegisterRequest("New Name", null));

            assertThat(user.getFirstName()).isEqualTo("Existing Name");
        }

        @Test
        @DisplayName("OWNER becoming PROFESSIONAL — CLIENT + OWNER + PROFESSIONAL coexist")
        void execute_ownerBecomeProfessional_allRolesActive() {
            user.addOwnerProfile();

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenReturn(user);

            useCase.execute(USER_ID, new ProfessionalRegisterRequest("Carlos", null));

            assertThat(user.isClient()).isTrue();
            assertThat(user.isOwner()).isTrue();
            assertThat(user.isProfessional()).isTrue();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Error cases
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("error cases")
    class ErrorCases {

        @Test
        @DisplayName("user not found → UserNotFoundException")
        void execute_userNotFound_throws() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    useCase.execute(USER_ID, new ProfessionalRegisterRequest("Carlos", null)))
                    .isInstanceOf(UserNotFoundException.class);

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("already PROFESSIONAL → UserAlreadyProfessionalException, no save")
        void execute_alreadyProfessional_throws() {
            user.addRole(RoleType.PROFESSIONAL);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            assertThatThrownBy(() ->
                    useCase.execute(USER_ID, new ProfessionalRegisterRequest("Carlos", null)))
                    .isInstanceOf(UserAlreadyProfessionalException.class);

            verify(userRepository, never()).save(any());
        }
    }
}
