package com.ospaulos.vupth.unit.affiliation;

import com.ospaulos.vupth.api.affiliation.dto.response.AffiliationRequestResponse;
import com.ospaulos.vupth.api.affiliation.usecase.RequestAffiliationUseCase;
import com.ospaulos.vupth.domain.affiliation.exception.AffiliationAlreadyPendingException;
import com.ospaulos.vupth.domain.affiliation.model.AffiliationRequest;
import com.ospaulos.vupth.domain.affiliation.model.AffiliationStatus;
import com.ospaulos.vupth.domain.affiliation.model.AffiliationType;
import com.ospaulos.vupth.domain.affiliation.repository.AffiliationRequestRepository;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("RequestAffiliationUseCase")
class RequestAffiliationUseCaseTest {

    private static final UUID PROF_ID = UUID.randomUUID();
    private static final UUID ESTAB_ID = UUID.randomUUID();

    @Mock ProfessionalRepository professionalRepository;
    @Mock EstablishmentRepository establishmentRepository;
    @Mock AffiliationRequestRepository affiliationRepository;

    @InjectMocks RequestAffiliationUseCase useCase;

    private Professional professional;
    private Establishment establishment;

    @BeforeEach
    void setUp() {
        BaseUser profUser = new BaseUser();
        profUser.setId(PROF_ID);
        profUser.setFirstName("Carlos");
        profUser.addProfessionalProfile();
        professional = profUser.getProfessional();

        establishment = new Establishment();
        establishment.setId(ESTAB_ID);
        establishment.setName("Barbearia");
    }

    @Nested
    @DisplayName("success cases")
    class SuccessCases {

        @Test
        @DisplayName("success: professional requests affiliation")
        void execute_success() {
            when(professionalRepository.findByIdWithBaseUser(PROF_ID)).thenReturn(Optional.of(professional));
            when(establishmentRepository.findByIdEager(ESTAB_ID)).thenReturn(Optional.of(establishment));
            when(affiliationRepository.existsByProfessional_IdAndTypeAndStatus(
                    PROF_ID, AffiliationType.REQUEST, AffiliationStatus.PENDING)).thenReturn(false);
            when(affiliationRepository.save(any())).thenAnswer(inv -> {
                AffiliationRequest ar = inv.getArgument(0);
                ar.setId(UUID.randomUUID());
                return ar;
            });

            AffiliationRequestResponse response = useCase.execute(PROF_ID, ESTAB_ID);

            assertThat(response.professionalId()).isEqualTo(PROF_ID);
            assertThat(response.establishmentId()).isEqualTo(ESTAB_ID);
            assertThat(response.type()).isEqualTo("REQUEST");
            assertThat(response.status()).isEqualTo("PENDING");
            verify(affiliationRepository).save(any());
        }
    }

    @Nested
    @DisplayName("error cases")
    class ErrorCases {

        @Test
        @DisplayName("professional not found → ProfessionalNotFoundException")
        void execute_professionalNotFound() {
            when(professionalRepository.findByIdWithBaseUser(PROF_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(PROF_ID, ESTAB_ID))
                    .isInstanceOf(ProfessionalNotFoundException.class);
        }

        @Test
        @DisplayName("professional already affiliated → ProfessionalAlreadyAffiliatedException")
        void execute_alreadyAffiliated() {
            Establishment other = new Establishment();
            other.setId(UUID.randomUUID());
            professional.setEstablishment(other);

            when(professionalRepository.findByIdWithBaseUser(PROF_ID)).thenReturn(Optional.of(professional));

            assertThatThrownBy(() -> useCase.execute(PROF_ID, ESTAB_ID))
                    .isInstanceOf(ProfessionalAlreadyAffiliatedException.class);
        }

        @Test
        @DisplayName("establishment not found → EstablishmentNotFoundException")
        void execute_establishmentNotFound() {
            when(professionalRepository.findByIdWithBaseUser(PROF_ID)).thenReturn(Optional.of(professional));
            when(establishmentRepository.findByIdEager(ESTAB_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(PROF_ID, ESTAB_ID))
                    .isInstanceOf(EstablishmentNotFoundException.class);
        }

        @Test
        @DisplayName("already pending (global) → AffiliationAlreadyPendingException")
        void execute_alreadyPending() {
            when(professionalRepository.findByIdWithBaseUser(PROF_ID)).thenReturn(Optional.of(professional));
            when(establishmentRepository.findByIdEager(ESTAB_ID)).thenReturn(Optional.of(establishment));
            when(affiliationRepository.existsByProfessional_IdAndTypeAndStatus(
                    PROF_ID, AffiliationType.REQUEST, AffiliationStatus.PENDING)).thenReturn(true);

            assertThatThrownBy(() -> useCase.execute(PROF_ID, ESTAB_ID))
                    .isInstanceOf(AffiliationAlreadyPendingException.class);
        }
    }
}
