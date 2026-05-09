package com.ospaulos.vupth.unit.professional;

import com.ospaulos.vupth.api.professional.dto.response.ProfessionalQuickCreateResponse;
import com.ospaulos.vupth.api.professional.usecase.AffiliateToEstablishmentUseCase;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalAlreadyAffiliatedException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
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
 * Unit tests — AffiliateToEstablishmentUseCase
 *
 * Business rules tested:
 *  - User must exist.
 *  - User must have PROFESSIONAL role.
 *  - Professional record must exist in repository.
 *  - Professional must NOT already be affiliated (establishment == null).
 *  - Establishment target must exist.
 *  - On success: professional.establishment is set and saved.
 *  - Response contains correct data.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AffiliateToEstablishmentUseCase")
class AffiliateToEstablishmentUseCaseTest {

    private static final UUID USER_ID    = UUID.randomUUID();
    private static final UUID ESTAB_ID   = UUID.randomUUID();
    private static final UUID ESTAB_ID_2 = UUID.randomUUID();

    @Mock UserRepository          userRepository;
    @Mock ProfessionalRepository  professionalRepository;
    @Mock EstablishmentRepository establishmentRepository;

    @InjectMocks AffiliateToEstablishmentUseCase useCase;

    private BaseUser      user;
    private Professional  professional;
    private Establishment targetEstablishment;

    @BeforeEach
    void setUp() {
        // User born as CLIENT, then gains PROFESSIONAL
        user = new BaseUser();
        user.setId(USER_ID);
        user.setEmail("prof@test.com");
        user.setPassword("$2a$hash");
        user.setFirstName("Carlos");
        user.addClientProfile();
        user.addProfessionalProfile();

        professional = user.getProfessional();
        professional.setSpecialty("Corte Masculino");
        // No establishment yet — not affiliated

        targetEstablishment = new Establishment();
        targetEstablishment.setId(ESTAB_ID);
        targetEstablishment.setName("Barbearia Teste");
    }

    // ─────────────────────────────────────────────────────────────
    // Happy path
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("success cases")
    class SuccessCases {

        @Test
        @DisplayName("success: professional with null establishment gets affiliated")
        void execute_nullEstablishment_affiliatesSuccessfully() {
            assertThat(professional.getEstablishment()).isNull();

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(professionalRepository.findById(USER_ID)).thenReturn(Optional.of(professional));
            when(establishmentRepository.findById(ESTAB_ID)).thenReturn(Optional.of(targetEstablishment));
            when(professionalRepository.save(any())).thenReturn(professional);

            ProfessionalQuickCreateResponse result = useCase.execute(USER_ID, ESTAB_ID);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(USER_ID);
            assertThat(result.establishmentId()).isEqualTo(ESTAB_ID);
            assertThat(professional.getEstablishment()).isEqualTo(targetEstablishment);
            verify(professionalRepository).save(professional);
        }

        @Test
        @DisplayName("success: response contains correct name, specialty and establishment")
        void execute_returnsCorrectResponse() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(professionalRepository.findById(USER_ID)).thenReturn(Optional.of(professional));
            when(establishmentRepository.findById(ESTAB_ID)).thenReturn(Optional.of(targetEstablishment));
            when(professionalRepository.save(any())).thenReturn(professional);

            ProfessionalQuickCreateResponse result = useCase.execute(USER_ID, ESTAB_ID);

            assertThat(result.name()).isEqualTo("Carlos");
            assertThat(result.specialty()).isEqualTo("Corte Masculino");
            assertThat(result.establishmentName()).isEqualTo("Barbearia Teste");
            assertThat(result.isAvailable()).isTrue();
            assertThat(result.message()).isNotBlank();
        }

        @Test
        @DisplayName("success: OWNER+PROFESSIONAL user can affiliate (solo shop)")
        void execute_ownerAndProfessional_canAffiliate() {
            user.addOwnerProfile();
            assertThat(user.isOwner()).isTrue();
            assertThat(user.isProfessional()).isTrue();

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(professionalRepository.findById(USER_ID)).thenReturn(Optional.of(professional));
            when(establishmentRepository.findById(ESTAB_ID)).thenReturn(Optional.of(targetEstablishment));
            when(professionalRepository.save(any())).thenReturn(professional);

            ProfessionalQuickCreateResponse result = useCase.execute(USER_ID, ESTAB_ID);

            assertThat(result.id()).isEqualTo(USER_ID);
            assertThat(professional.getEstablishment()).isEqualTo(targetEstablishment);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Error cases
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("error cases")
    class ErrorCases {

        @Test
        @DisplayName("user not found → UserNotFoundException, no further calls")
        void execute_userNotFound_throws() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(USER_ID, ESTAB_ID))
                    .isInstanceOf(UserNotFoundException.class);

            verify(professionalRepository, never()).findById(any());
            verify(professionalRepository, never()).save(any());
            verify(establishmentRepository, never()).findById(any());
        }

        @Test
        @DisplayName("user has no PROFESSIONAL role → ProfessionalNotFoundException")
        void execute_notProfessional_throws() {
            BaseUser clientOnly = new BaseUser();
            clientOnly.setId(USER_ID);
            clientOnly.addClientProfile(); // only CLIENT

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(clientOnly));

            assertThatThrownBy(() -> useCase.execute(USER_ID, ESTAB_ID))
                    .isInstanceOf(ProfessionalNotFoundException.class);

            verify(professionalRepository, never()).findById(any());
            verify(professionalRepository, never()).save(any());
        }

        @Test
        @DisplayName("professional not found in repo → ProfessionalNotFoundException")
        void execute_professionalNotInRepo_throws() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(professionalRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(USER_ID, ESTAB_ID))
                    .isInstanceOf(ProfessionalNotFoundException.class);

            verify(establishmentRepository, never()).findById(any());
            verify(professionalRepository, never()).save(any());
        }

        @Test
        @DisplayName("professional already affiliated → ProfessionalAlreadyAffiliatedException, no save")
        void execute_alreadyAffiliated_throws() {
            // Set current establishment
            Establishment current = new Establishment();
            current.setId(ESTAB_ID_2);
            current.setName("Loja Atual");
            professional.setEstablishment(current);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(professionalRepository.findById(USER_ID)).thenReturn(Optional.of(professional));

            assertThatThrownBy(() -> useCase.execute(USER_ID, ESTAB_ID))
                    .isInstanceOf(ProfessionalAlreadyAffiliatedException.class)
                    .hasMessageContaining(USER_ID.toString())
                    .hasMessageContaining(ESTAB_ID_2.toString());

            verify(establishmentRepository, never()).findById(any());
            verify(professionalRepository, never()).save(any());
        }

        @Test
        @DisplayName("establishment not found → EstablishmentNotFoundException, no save")
        void execute_establishmentNotFound_throws() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(professionalRepository.findById(USER_ID)).thenReturn(Optional.of(professional));
            when(establishmentRepository.findById(ESTAB_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(USER_ID, ESTAB_ID))
                    .isInstanceOf(EstablishmentNotFoundException.class);

            verify(professionalRepository, never()).save(any());
        }

        @Test
        @DisplayName("already affiliated: message contains both professionalId and current establishmentId")
        void execute_alreadyAffiliated_messageContainsIds() {
            Establishment current = new Establishment();
            current.setId(ESTAB_ID_2);
            professional.setEstablishment(current);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(professionalRepository.findById(USER_ID)).thenReturn(Optional.of(professional));

            Throwable thrown = catchThrowable(() -> useCase.execute(USER_ID, ESTAB_ID));

            assertThat(thrown)
                    .isInstanceOf(ProfessionalAlreadyAffiliatedException.class)
                    .hasMessageContaining(USER_ID.toString())
                    .hasMessageContaining(ESTAB_ID_2.toString());
        }
    }
}
