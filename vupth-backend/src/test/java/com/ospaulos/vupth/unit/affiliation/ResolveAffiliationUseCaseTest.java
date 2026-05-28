package com.ospaulos.vupth.unit.affiliation;

import com.ospaulos.vupth.api.affiliation.dto.response.AffiliationRequestResponse;
import com.ospaulos.vupth.api.affiliation.usecase.ResolveAffiliationUseCase;
import com.ospaulos.vupth.domain.affiliation.exception.AffiliationAccessDeniedException;
import com.ospaulos.vupth.domain.affiliation.exception.AffiliationNotFoundException;
import com.ospaulos.vupth.domain.affiliation.model.AffiliationRequest;
import com.ospaulos.vupth.domain.affiliation.model.AffiliationStatus;
import com.ospaulos.vupth.domain.affiliation.model.AffiliationType;
import com.ospaulos.vupth.domain.affiliation.repository.AffiliationRequestRepository;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.owner.model.Owner;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalAlreadyAffiliatedException;
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
@DisplayName("ResolveAffiliationUseCase")
class ResolveAffiliationUseCaseTest {

    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID PROF_ID = UUID.randomUUID();
    private static final UUID ESTAB_ID = UUID.randomUUID();
    private static final UUID AFFILIATION_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();

    @Mock AffiliationRequestRepository affiliationRepository;
    @Mock ProfessionalRepository professionalRepository;

    @InjectMocks ResolveAffiliationUseCase useCase;

    private Professional professional;
    private Establishment establishment;
    private Owner owner;

    @BeforeEach
    void setUp() {
        BaseUser ownerUser = new BaseUser();
        ownerUser.setId(OWNER_ID);
        owner = new Owner(ownerUser);

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

    private AffiliationRequest buildPending(AffiliationType type) {
        AffiliationRequest ar = new AffiliationRequest(professional, establishment, type);
        ar.setId(AFFILIATION_ID);
        return ar;
    }

    // ─────────────────────────────────────────────────────────────
    // ACCEPT
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("accept")
    class AcceptTests {

        @Test
        @DisplayName("owner accepts REQUEST → professional affiliated")
        void accept_request_byOwner() {
            AffiliationRequest ar = buildPending(AffiliationType.REQUEST);
            when(affiliationRepository.findByIdEager(AFFILIATION_ID)).thenReturn(Optional.of(ar));
            when(affiliationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            AffiliationRequestResponse response = useCase.accept(OWNER_ID, AFFILIATION_ID);

            assertThat(response.status()).isEqualTo("ACCEPTED");
            assertThat(professional.getEstablishment()).isEqualTo(establishment);
            verify(professionalRepository).save(professional);
        }

        @Test
        @DisplayName("professional accepts INVITATION → professional affiliated")
        void accept_invitation_byProfessional() {
            AffiliationRequest ar = buildPending(AffiliationType.INVITATION);
            when(affiliationRepository.findByIdEager(AFFILIATION_ID)).thenReturn(Optional.of(ar));
            when(affiliationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            AffiliationRequestResponse response = useCase.accept(PROF_ID, AFFILIATION_ID);

            assertThat(response.status()).isEqualTo("ACCEPTED");
            assertThat(professional.getEstablishment()).isEqualTo(establishment);
        }

        @Test
        @DisplayName("wrong user accepts REQUEST → AffiliationAccessDeniedException")
        void accept_request_wrongUser() {
            AffiliationRequest ar = buildPending(AffiliationType.REQUEST);
            when(affiliationRepository.findByIdEager(AFFILIATION_ID)).thenReturn(Optional.of(ar));

            assertThatThrownBy(() -> useCase.accept(OTHER_USER_ID, AFFILIATION_ID))
                    .isInstanceOf(AffiliationAccessDeniedException.class);
        }

        @Test
        @DisplayName("professional tries to accept own REQUEST → AffiliationAccessDeniedException")
        void accept_request_byProfessional() {
            AffiliationRequest ar = buildPending(AffiliationType.REQUEST);
            when(affiliationRepository.findByIdEager(AFFILIATION_ID)).thenReturn(Optional.of(ar));

            assertThatThrownBy(() -> useCase.accept(PROF_ID, AFFILIATION_ID))
                    .isInstanceOf(AffiliationAccessDeniedException.class);
        }

        @Test
        @DisplayName("accept when professional already affiliated → ProfessionalAlreadyAffiliatedException")
        void accept_alreadyAffiliated() {
            Establishment other = new Establishment();
            other.setId(UUID.randomUUID());
            professional.setEstablishment(other);

            AffiliationRequest ar = buildPending(AffiliationType.REQUEST);
            when(affiliationRepository.findByIdEager(AFFILIATION_ID)).thenReturn(Optional.of(ar));

            assertThatThrownBy(() -> useCase.accept(OWNER_ID, AFFILIATION_ID))
                    .isInstanceOf(ProfessionalAlreadyAffiliatedException.class);
        }

        @Test
        @DisplayName("affiliation not found → AffiliationNotFoundException")
        void accept_notFound() {
            when(affiliationRepository.findByIdEager(AFFILIATION_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.accept(OWNER_ID, AFFILIATION_ID))
                    .isInstanceOf(AffiliationNotFoundException.class);
        }

        @Test
        @DisplayName("already resolved → AffiliationAccessDeniedException")
        void accept_alreadyResolved() {
            AffiliationRequest ar = buildPending(AffiliationType.REQUEST);
            ar.reject();
            when(affiliationRepository.findByIdEager(AFFILIATION_ID)).thenReturn(Optional.of(ar));

            assertThatThrownBy(() -> useCase.accept(OWNER_ID, AFFILIATION_ID))
                    .isInstanceOf(AffiliationAccessDeniedException.class);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // REJECT
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reject")
    class RejectTests {

        @Test
        @DisplayName("owner rejects REQUEST → status REJECTED")
        void reject_request_byOwner() {
            AffiliationRequest ar = buildPending(AffiliationType.REQUEST);
            when(affiliationRepository.findByIdEager(AFFILIATION_ID)).thenReturn(Optional.of(ar));
            when(affiliationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            AffiliationRequestResponse response = useCase.reject(OWNER_ID, AFFILIATION_ID);

            assertThat(response.status()).isEqualTo("REJECTED");
            assertThat(professional.getEstablishment()).isNull();
        }

        @Test
        @DisplayName("professional rejects INVITATION → status REJECTED")
        void reject_invitation_byProfessional() {
            AffiliationRequest ar = buildPending(AffiliationType.INVITATION);
            when(affiliationRepository.findByIdEager(AFFILIATION_ID)).thenReturn(Optional.of(ar));
            when(affiliationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            AffiliationRequestResponse response = useCase.reject(PROF_ID, AFFILIATION_ID);

            assertThat(response.status()).isEqualTo("REJECTED");
        }

        @Test
        @DisplayName("wrong user rejects → AffiliationAccessDeniedException")
        void reject_wrongUser() {
            AffiliationRequest ar = buildPending(AffiliationType.REQUEST);
            when(affiliationRepository.findByIdEager(AFFILIATION_ID)).thenReturn(Optional.of(ar));

            assertThatThrownBy(() -> useCase.reject(OTHER_USER_ID, AFFILIATION_ID))
                    .isInstanceOf(AffiliationAccessDeniedException.class);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // CANCEL
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cancel")
    class CancelTests {

        @Test
        @DisplayName("professional cancels own REQUEST → status CANCELLED")
        void cancel_request_byProfessional() {
            AffiliationRequest ar = buildPending(AffiliationType.REQUEST);
            when(affiliationRepository.findByIdEager(AFFILIATION_ID)).thenReturn(Optional.of(ar));
            when(affiliationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            AffiliationRequestResponse response = useCase.cancel(PROF_ID, AFFILIATION_ID);

            assertThat(response.status()).isEqualTo("CANCELLED");
        }

        @Test
        @DisplayName("owner cancels own INVITATION → status CANCELLED")
        void cancel_invitation_byOwner() {
            AffiliationRequest ar = buildPending(AffiliationType.INVITATION);
            when(affiliationRepository.findByIdEager(AFFILIATION_ID)).thenReturn(Optional.of(ar));
            when(affiliationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            AffiliationRequestResponse response = useCase.cancel(OWNER_ID, AFFILIATION_ID);

            assertThat(response.status()).isEqualTo("CANCELLED");
        }

        @Test
        @DisplayName("owner tries to cancel REQUEST (not initiator) → AffiliationAccessDeniedException")
        void cancel_request_byOwner() {
            AffiliationRequest ar = buildPending(AffiliationType.REQUEST);
            when(affiliationRepository.findByIdEager(AFFILIATION_ID)).thenReturn(Optional.of(ar));

            assertThatThrownBy(() -> useCase.cancel(OWNER_ID, AFFILIATION_ID))
                    .isInstanceOf(AffiliationAccessDeniedException.class);
        }

        @Test
        @DisplayName("professional tries to cancel INVITATION (not initiator) → AffiliationAccessDeniedException")
        void cancel_invitation_byProfessional() {
            AffiliationRequest ar = buildPending(AffiliationType.INVITATION);
            when(affiliationRepository.findByIdEager(AFFILIATION_ID)).thenReturn(Optional.of(ar));

            assertThatThrownBy(() -> useCase.cancel(PROF_ID, AFFILIATION_ID))
                    .isInstanceOf(AffiliationAccessDeniedException.class);
        }
    }
}
