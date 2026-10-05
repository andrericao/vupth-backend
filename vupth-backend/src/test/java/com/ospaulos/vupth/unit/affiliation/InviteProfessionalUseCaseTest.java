package com.ospaulos.vupth.unit.affiliation;

import com.ospaulos.vupth.api.affiliation.dto.response.AffiliationRequestResponse;
import com.ospaulos.vupth.api.affiliation.usecase.InviteProfessionalUseCase;
import com.ospaulos.vupth.domain.affiliation.exception.AffiliationAlreadyPendingException;
import com.ospaulos.vupth.domain.affiliation.model.AffiliationRequest;
import com.ospaulos.vupth.domain.affiliation.model.AffiliationStatus;
import com.ospaulos.vupth.domain.affiliation.repository.AffiliationRequestRepository;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("InviteProfessionalUseCase")
class InviteProfessionalUseCaseTest {

    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID OTHER_OWNER_ID = UUID.randomUUID();
    private static final UUID ESTAB_ID = UUID.randomUUID();
    private static final UUID PROF_ID = UUID.randomUUID();

    @Mock EstablishmentRepository establishmentRepository;
    @Mock ProfessionalRepository professionalRepository;
    @Mock AffiliationRequestRepository affiliationRepository;

    @Mock com.ospaulos.vupth.domain.affiliation.service.AffiliationNotifier notifier;
    @InjectMocks InviteProfessionalUseCase useCase;

    private Establishment establishment;
    private Professional professional;

    @BeforeEach
    void setUp() {
        BaseUser ownerUser = new BaseUser();
        ownerUser.setId(OWNER_ID);
        Owner owner = new Owner(ownerUser);

        establishment = new Establishment();
        establishment.setId(ESTAB_ID);
        establishment.setName("Barbearia");
        establishment.setOwner(owner);

        BaseUser profUser = new BaseUser();
        profUser.setId(PROF_ID);
        profUser.setFirstName("Carlos");
        profUser.addProfessionalProfile();
        professional = profUser.getProfessional();
    }

    @Nested
    @DisplayName("success cases")
    class SuccessCases {

        @Test
        @DisplayName("success: owner invites himself — auto-accepted")
        void execute_selfInvite_autoAccepted() {
            BaseUser selfUser = new BaseUser();
            selfUser.setId(OWNER_ID);
            Owner selfOwner = new Owner(selfUser);

            Establishment selfEstab = new Establishment();
            selfEstab.setId(ESTAB_ID);
            selfEstab.setName("Minha Barbearia");
            selfEstab.setOwner(selfOwner);

            selfUser.addProfessionalProfile();
            Professional selfProf = selfUser.getProfessional();

            when(establishmentRepository.findByIdEager(ESTAB_ID)).thenReturn(Optional.of(selfEstab));
            when(professionalRepository.findByIdWithBaseUser(OWNER_ID)).thenReturn(Optional.of(selfProf));
            when(affiliationRepository.existsByProfessional_IdAndEstablishment_IdAndStatus(
                    OWNER_ID, ESTAB_ID, AffiliationStatus.PENDING)).thenReturn(false);
            when(affiliationRepository.save(any())).thenAnswer(inv -> {
                AffiliationRequest ar = inv.getArgument(0);
                ar.setId(UUID.randomUUID());
                return ar;
            });

            AffiliationRequestResponse response = useCase.execute(OWNER_ID, ESTAB_ID, OWNER_ID);

            assertThat(response.type()).isEqualTo("INVITATION");
            assertThat(response.status()).isEqualTo("ACCEPTED");
            assertThat(selfProf.getEstablishment()).isEqualTo(selfEstab);
            verify(professionalRepository).save(selfProf);
        }

        @Test
        @DisplayName("success: owner invites unaffiliated professional")
        void execute_success() {
            when(establishmentRepository.findByIdEager(ESTAB_ID)).thenReturn(Optional.of(establishment));
            when(professionalRepository.findByIdWithBaseUser(PROF_ID)).thenReturn(Optional.of(professional));
            when(affiliationRepository.existsByProfessional_IdAndEstablishment_IdAndStatus(
                    PROF_ID, ESTAB_ID, AffiliationStatus.PENDING)).thenReturn(false);
            when(affiliationRepository.save(any())).thenAnswer(inv -> {
                AffiliationRequest ar = inv.getArgument(0);
                ar.setId(UUID.randomUUID());
                return ar;
            });

            AffiliationRequestResponse response = useCase.execute(OWNER_ID, ESTAB_ID, PROF_ID);

            assertThat(response.type()).isEqualTo("INVITATION");
            assertThat(response.status()).isEqualTo("PENDING");
            assertThat(response.professionalId()).isEqualTo(PROF_ID);
            assertThat(response.establishmentId()).isEqualTo(ESTAB_ID);
        }
    }

    @Nested
    @DisplayName("error cases")
    class ErrorCases {

        @Test
        @DisplayName("establishment not found → EstablishmentNotFoundException")
        void execute_establishmentNotFound() {
            when(establishmentRepository.findByIdEager(ESTAB_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(OWNER_ID, ESTAB_ID, PROF_ID))
                    .isInstanceOf(EstablishmentNotFoundException.class);
        }

        @Test
        @DisplayName("wrong owner → EstablishmentAccessDeniedException")
        void execute_wrongOwner() {
            when(establishmentRepository.findByIdEager(ESTAB_ID)).thenReturn(Optional.of(establishment));

            assertThatThrownBy(() -> useCase.execute(OTHER_OWNER_ID, ESTAB_ID, PROF_ID))
                    .isInstanceOf(EstablishmentAccessDeniedException.class);
        }

        @Test
        @DisplayName("professional not found → ProfessionalNotFoundException")
        void execute_professionalNotFound() {
            when(establishmentRepository.findByIdEager(ESTAB_ID)).thenReturn(Optional.of(establishment));
            when(professionalRepository.findByIdWithBaseUser(PROF_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(OWNER_ID, ESTAB_ID, PROF_ID))
                    .isInstanceOf(ProfessionalNotFoundException.class);
        }

        @Test
        @DisplayName("professional already affiliated → ProfessionalAlreadyAffiliatedException")
        void execute_alreadyAffiliated() {
            Establishment other = new Establishment();
            other.setId(UUID.randomUUID());
            professional.setEstablishment(other);

            when(establishmentRepository.findByIdEager(ESTAB_ID)).thenReturn(Optional.of(establishment));
            when(professionalRepository.findByIdWithBaseUser(PROF_ID)).thenReturn(Optional.of(professional));

            assertThatThrownBy(() -> useCase.execute(OWNER_ID, ESTAB_ID, PROF_ID))
                    .isInstanceOf(ProfessionalAlreadyAffiliatedException.class);
        }

        @Test
        @DisplayName("already pending → AffiliationAlreadyPendingException")
        void execute_alreadyPending() {
            when(establishmentRepository.findByIdEager(ESTAB_ID)).thenReturn(Optional.of(establishment));
            when(professionalRepository.findByIdWithBaseUser(PROF_ID)).thenReturn(Optional.of(professional));
            when(affiliationRepository.existsByProfessional_IdAndEstablishment_IdAndStatus(
                    PROF_ID, ESTAB_ID, AffiliationStatus.PENDING)).thenReturn(true);

            assertThatThrownBy(() -> useCase.execute(OWNER_ID, ESTAB_ID, PROF_ID))
                    .isInstanceOf(AffiliationAlreadyPendingException.class);
        }
    }
}
