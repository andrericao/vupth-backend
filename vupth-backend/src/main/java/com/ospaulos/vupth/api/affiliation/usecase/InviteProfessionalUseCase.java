package com.ospaulos.vupth.api.affiliation.usecase;

import com.ospaulos.vupth.api.affiliation.dto.response.AffiliationRequestResponse;
import com.ospaulos.vupth.domain.affiliation.exception.AffiliationAlreadyPendingException;
import com.ospaulos.vupth.domain.affiliation.model.AffiliationRequest;
import com.ospaulos.vupth.domain.affiliation.model.AffiliationStatus;
import com.ospaulos.vupth.domain.affiliation.model.AffiliationType;
import com.ospaulos.vupth.domain.affiliation.repository.AffiliationRequestRepository;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalAlreadyAffiliatedException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InviteProfessionalUseCase {

    private final EstablishmentRepository establishmentRepository;
    private final ProfessionalRepository professionalRepository;
    private final AffiliationRequestRepository affiliationRepository;
    private final com.ospaulos.vupth.domain.affiliation.service.AffiliationNotifier notifier;

    @Transactional
    public AffiliationRequestResponse execute(UUID ownerId, UUID establishmentId, UUID professionalId) {
        log.info("[Affiliation] invite — owner={} establishment={} professional={}",
                ownerId, establishmentId, professionalId);

        Establishment establishment = establishmentRepository.findByIdEager(establishmentId)
                .orElseThrow(() -> new EstablishmentNotFoundException(establishmentId));

        if (establishment.getOwner() == null ||
                !establishment.getOwner().getId().equals(ownerId)) {
            throw new EstablishmentAccessDeniedException(
                    "You can only invite professionals to your own establishments.");
        }

        Professional professional = professionalRepository.findByIdWithBaseUser(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        if (professional.getEstablishment() != null) {
            throw new ProfessionalAlreadyAffiliatedException(
                    professionalId, professional.getEstablishment().getId());
        }

        if (affiliationRepository.existsByProfessional_IdAndEstablishment_IdAndStatus(
                professionalId, establishmentId, AffiliationStatus.PENDING)) {
            throw new AffiliationAlreadyPendingException(professionalId, establishmentId);
        }

        AffiliationRequest invitation = new AffiliationRequest(professional, establishment, AffiliationType.INVITATION);

        boolean selfInvite = ownerId.equals(professionalId);
        if (selfInvite) {
            invitation.accept();
            professional.setEstablishment(establishment);
            professionalRepository.save(professional);
        }

        AffiliationRequest saved = affiliationRepository.save(invitation);
        if (!selfInvite) {
            notifier.created(saved); // DEC-19: o convidado sabe por push e e-mail
        }

        log.info("[Affiliation] invitation {} — id={} selfInvite={}",
                selfInvite ? "auto-accepted" : "created", saved.getId(), selfInvite);
        return AffiliationRequestResponse.fromEntity(saved);
    }
}
