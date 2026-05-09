package com.ospaulos.vupth.unit.establishment;

import com.ospaulos.vupth.api.establishment.usecase.AddProfessionalToEstablishmentUseCase;
import com.ospaulos.vupth.api.professional.dto.response.ProfessionalQuickCreateResponse;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.owner.model.Owner;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalAlreadyAffiliatedException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
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
 * Unit tests — AddProfessionalToEstablishmentUseCase
 *
 * Business rules:
 *  - Establishment must exist.
 *  - Owner must own the establishment.
 *  - Professional must exist.
 *  - Professional must not already be affiliated to any establishment.
 *  - On success: professional.establishment is set and saved.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AddProfessionalToEstablishmentUseCase")
class AddProfessionalToEstablishmentUseCaseTest {

    private static final UUID OWNER_ID   = UUID.randomUUID();
    private static final UUID OTHER_OWNER_ID = UUID.randomUUID();
    private static final UUID ESTAB_ID   = UUID.randomUUID();
    private static final UUID PROF_ID    = UUID.randomUUID();
    private static final UUID OTHER_ESTAB_ID = UUID.randomUUID();

    @Mock EstablishmentRepository establishmentRepository;
    @Mock ProfessionalRepository  professionalRepository;

    @InjectMocks AddProfessionalToEstablishmentUseCase useCase;

    private Establishment establishment;
    private Professional  professional;
    private BaseUser      profUser;
    private Owner         owner;

    @BeforeEach
    void setUp() {
        // Owner
        BaseUser ownerUser = new BaseUser();
        ownerUser.setId(OWNER_ID);
        ownerUser.setFirstName("Owner");
        owner = new Owner(ownerUser);

        // Establishment owned by OWNER_ID
        establishment = new Establishment();
        establishment.setId(ESTAB_ID);
        establishment.setName("Barbearia do Owner");
        establishment.setOwner(owner);

        // Professional with no current establishment
        profUser = new BaseUser();
        profUser.setId(PROF_ID);
        profUser.setFirstName("Carlos");
        profUser.addClientProfile();
        profUser.addProfessionalProfile();

        professional = profUser.getProfessional();
        professional.setSpecialty("Corte Masculino");
        // establishment = null (not yet affiliated)
    }

    // ─────────────────────────────────────────────────────────────
    // Happy path
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("success cases")
    class SuccessCases {

        @Test
        @DisplayName("success: owner adds unaffiliated professional to their establishment")
        void execute_success_linksUnaffiliatedProfessional() {
            assertThat(professional.getEstablishment()).isNull();

            when(establishmentRepository.findByIdEager(ESTAB_ID)).thenReturn(Optional.of(establishment));
            when(professionalRepository.findByIdWithBaseUser(PROF_ID)).thenReturn(Optional.of(professional));
            when(professionalRepository.save(any())).thenReturn(professional);

            ProfessionalQuickCreateResponse result =
                    useCase.execute(OWNER_ID, ESTAB_ID, PROF_ID);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(PROF_ID);
            assertThat(result.establishmentId()).isEqualTo(ESTAB_ID);
            assertThat(professional.getEstablishment()).isEqualTo(establishment);
            verify(professionalRepository).save(professional);
        }

        @Test
        @DisplayName("success: response contains correct name and establishment")
        void execute_returnsCorrectResponse() {
            when(establishmentRepository.findByIdEager(ESTAB_ID)).thenReturn(Optional.of(establishment));
            when(professionalRepository.findByIdWithBaseUser(PROF_ID)).thenReturn(Optional.of(professional));
            when(professionalRepository.save(any())).thenReturn(professional);

            ProfessionalQuickCreateResponse result =
                    useCase.execute(OWNER_ID, ESTAB_ID, PROF_ID);

            assertThat(result.name()).isEqualTo("Carlos");
            assertThat(result.specialty()).isEqualTo("Corte Masculino");
            assertThat(result.establishmentName()).isEqualTo("Barbearia do Owner");
            assertThat(result.isAvailable()).isTrue();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Error cases
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("error cases")
    class ErrorCases {

        @Test
        @DisplayName("establishment not found → EstablishmentNotFoundException")
        void execute_establishmentNotFound_throws() {
            when(establishmentRepository.findByIdEager(ESTAB_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(OWNER_ID, ESTAB_ID, PROF_ID))
                    .isInstanceOf(EstablishmentNotFoundException.class);

            verify(professionalRepository, never()).findByIdWithBaseUser(any());
            verify(professionalRepository, never()).save(any());
        }

        @Test
        @DisplayName("owner does not own the establishment → EstablishmentAccessDeniedException")
        void execute_wrongOwner_throws() {
            when(establishmentRepository.findByIdEager(ESTAB_ID)).thenReturn(Optional.of(establishment));

            // OTHER_OWNER_ID is not the owner
            assertThatThrownBy(() -> useCase.execute(OTHER_OWNER_ID, ESTAB_ID, PROF_ID))
                    .isInstanceOf(EstablishmentAccessDeniedException.class);

            verify(professionalRepository, never()).findByIdWithBaseUser(any());
            verify(professionalRepository, never()).save(any());
        }

        @Test
        @DisplayName("establishment has no owner → EstablishmentAccessDeniedException")
        void execute_noOwner_throws() {
            establishment.setOwner(null);
            when(establishmentRepository.findByIdEager(ESTAB_ID)).thenReturn(Optional.of(establishment));

            assertThatThrownBy(() -> useCase.execute(OWNER_ID, ESTAB_ID, PROF_ID))
                    .isInstanceOf(EstablishmentAccessDeniedException.class);
        }

        @Test
        @DisplayName("professional not found → ProfessionalNotFoundException")
        void execute_professionalNotFound_throws() {
            when(establishmentRepository.findByIdEager(ESTAB_ID)).thenReturn(Optional.of(establishment));
            when(professionalRepository.findByIdWithBaseUser(PROF_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(OWNER_ID, ESTAB_ID, PROF_ID))
                    .isInstanceOf(ProfessionalNotFoundException.class);

            verify(professionalRepository, never()).save(any());
        }

        @Test
        @DisplayName("professional already affiliated → ProfessionalAlreadyAffiliatedException, no save")
        void execute_alreadyAffiliated_throws() {
            // Give professional a current establishment
            Establishment currentEstab = new Establishment();
            currentEstab.setId(OTHER_ESTAB_ID);
            professional.setEstablishment(currentEstab);

            when(establishmentRepository.findByIdEager(ESTAB_ID)).thenReturn(Optional.of(establishment));
            when(professionalRepository.findByIdWithBaseUser(PROF_ID)).thenReturn(Optional.of(professional));

            assertThatThrownBy(() -> useCase.execute(OWNER_ID, ESTAB_ID, PROF_ID))
                    .isInstanceOf(ProfessionalAlreadyAffiliatedException.class)
                    .hasMessageContaining(PROF_ID.toString())
                    .hasMessageContaining(OTHER_ESTAB_ID.toString());

            verify(professionalRepository, never()).save(any());
        }
    }
}
