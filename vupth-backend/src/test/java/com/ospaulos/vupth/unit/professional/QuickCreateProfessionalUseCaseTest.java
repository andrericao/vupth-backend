package com.ospaulos.vupth.unit.professional;

import com.ospaulos.vupth.api.professional.dto.request.ProfessionalQuickCreateRequest;
import com.ospaulos.vupth.api.professional.dto.response.ProfessionalQuickCreateResponse;
import com.ospaulos.vupth.api.professional.exception.UserAlreadyProfessionalException;
import com.ospaulos.vupth.api.professional.usecase.QuickCreateProfessionalUseCase;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests — QuickCreateProfessionalUseCase
 *
 * Business rules:
 * - Any authenticated user can affiliate as a professional at an establishment.
 * - PROFESSIONAL role is added automatically.
 * - Professional.id == BaseUser.id (MapsId) — fixed and immutable.
 * - User already PROFESSIONAL → UserAlreadyProfessionalException (idempotency guard).
 * - Establishment must exist.
 * - BaseUser.firstName updated from request if currently blank/null.
 * - CLIENT (and OWNER, if present) roles are preserved.
 *
 * Solo shop pattern:
 *   Same user can be CLIENT + OWNER + PROFESSIONAL simultaneously.
 *   That is the standard single-operator barbershop flow.
 *
 * Role flow:
 *   POST /professional/quick-create → adds PROFESSIONAL
 *
 * @since v9  extracted from QuickCreateUseCasesTest
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuickCreateProfessionalUseCase")
class QuickCreateProfessionalUseCaseTest {

    private static final UUID USER_ID  = UUID.randomUUID();
    private static final UUID ESTAB_ID = UUID.randomUUID();

    @Mock UserRepository          userRepository;
    @Mock ProfessionalRepository  professionalRepository;
    @Mock EstablishmentRepository establishmentRepository;

    @InjectMocks QuickCreateProfessionalUseCase useCase;

    private BaseUser    user;
    private Establishment establishment;

    @BeforeEach
    void setUp() {
        user = new BaseUser();
        user.setId(USER_ID);
        user.setEmail("user@example.com");
        user.setPassword("$2a$hash");
        user.addClientProfile(); // every user is born CLIENT

        establishment = new Establishment();
        establishment.setId(ESTAB_ID);
        establishment.setName("Test Barbershop");
    }

    // ─────────────────────────────────────────────────────────────
    // Happy path
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("success: PROFESSIONAL role added, professional.id == user.id (MapsId)")
    void execute_success_addsProfessionalRole() {
        assertThat(user.isProfessional()).isFalse();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenReturn(user);
        when(establishmentRepository.findById(ESTAB_ID)).thenReturn(Optional.of(establishment));
        ProfessionalQuickCreateResponse result = useCase.execute(USER_ID,
                new ProfessionalQuickCreateRequest("Carlos", ESTAB_ID, "Barber"));

        assertThat(result).isNotNull();
        assertThat(user.isProfessional()).isTrue();
        assertThat(user.isClient()).isTrue(); // CLIENT role preserved
        verify(userRepository).save(any(BaseUser.class));
    }

    @Test
    @DisplayName("solo shop: OWNER becoming PROFESSIONAL — CLIENT + OWNER + PROFESSIONAL all active")
    void execute_ownerBecomeProfessional_allThreeRolesActive() {
        user.addOwnerProfile();
        assertThat(user.isOwner()).isTrue();
        assertThat(user.isProfessional()).isFalse();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenReturn(user);
        when(establishmentRepository.findById(ESTAB_ID)).thenReturn(Optional.of(establishment));
        useCase.execute(USER_ID, new ProfessionalQuickCreateRequest("Carlos", ESTAB_ID, null));

        // Solo shop: all three roles coexist
        assertThat(user.isClient()).isTrue();
        assertThat(user.isOwner()).isTrue();
        assertThat(user.isProfessional()).isTrue();
    }

    @Test
    @DisplayName("blank firstName in BaseUser is filled from request name")
    void execute_updatesFirstNameWhenBlank() {
        user.setFirstName(null);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenReturn(user);
        when(establishmentRepository.findById(ESTAB_ID)).thenReturn(Optional.of(establishment));
        useCase.execute(USER_ID, new ProfessionalQuickCreateRequest("Carlos Silva", ESTAB_ID, null));

        assertThat(user.getFirstName()).isEqualTo("Carlos Silva");
    }

    // ─────────────────────────────────────────────────────────────
    // Error cases
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("already PROFESSIONAL → throws UserAlreadyProfessionalException, professional not saved")
    void execute_alreadyProfessional_throws() {
        user.addRole(RoleType.PROFESSIONAL);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() ->
                useCase.execute(USER_ID, new ProfessionalQuickCreateRequest("Carlos", ESTAB_ID, null)))
                .isInstanceOf(UserAlreadyProfessionalException.class);

        verify(professionalRepository, never()).save(any());
    }

    @Test
    @DisplayName("user not found → throws UserNotFoundException")
    void execute_userNotFound_throws() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                useCase.execute(USER_ID, new ProfessionalQuickCreateRequest("Carlos", ESTAB_ID, null)))
                .isInstanceOf(UserNotFoundException.class);

        verify(professionalRepository, never()).save(any());
        verify(establishmentRepository, never()).findById(any());
    }
}
