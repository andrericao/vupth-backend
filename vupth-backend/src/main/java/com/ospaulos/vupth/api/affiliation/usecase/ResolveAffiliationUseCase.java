package com.ospaulos.vupth.api.affiliation.usecase;

import com.ospaulos.vupth.api.affiliation.dto.response.AffiliationRequestResponse;
import com.ospaulos.vupth.domain.affiliation.exception.AffiliationAccessDeniedException;
import com.ospaulos.vupth.domain.affiliation.exception.AffiliationNotFoundException;
import com.ospaulos.vupth.domain.affiliation.model.AffiliationRequest;
import com.ospaulos.vupth.domain.affiliation.model.AffiliationStatus;
import com.ospaulos.vupth.domain.affiliation.model.AffiliationType;
import com.ospaulos.vupth.domain.affiliation.repository.AffiliationRequestRepository;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalAlreadyAffiliatedException;
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
public class ResolveAffiliationUseCase {

    private final AffiliationRequestRepository affiliationRepository;
    private final ProfessionalRepository professionalRepository;
    private final com.ospaulos.vupth.domain.affiliation.service.AffiliationNotifier notifier;

    @Transactional
    public AffiliationRequestResponse accept(UUID userId, UUID affiliationId) {
        AffiliationRequest ar = loadAndValidateCounterpart(userId, affiliationId);

        Professional professional = ar.getProfessional();
        if (professional.getEstablishment() != null) {
            throw new ProfessionalAlreadyAffiliatedException(
                    professional.getId(), professional.getEstablishment().getId());
        }

        ar.accept();
        professional.setEstablishment(ar.getEstablishment());
        professionalRepository.save(professional);
        AffiliationRequest saved = affiliationRepository.save(ar);

        notifier.resolved(saved); // DEC-19: quem pediu sabe do aceite

        log.info("[Affiliation] accepted — id={} professional={} establishment={}",
                affiliationId, professional.getId(), ar.getEstablishment().getId());
        return AffiliationRequestResponse.fromEntity(saved);
    }

    @Transactional
    public AffiliationRequestResponse reject(UUID userId, UUID affiliationId) {
        AffiliationRequest ar = loadAndValidateCounterpart(userId, affiliationId);

        ar.reject();
        AffiliationRequest saved = affiliationRepository.save(ar);
        notifier.resolved(saved); // DEC-19: quem pediu sabe da recusa

        log.info("[Affiliation] rejected — id={}", affiliationId);
        return AffiliationRequestResponse.fromEntity(saved);
    }

    @Transactional
    public AffiliationRequestResponse cancel(UUID userId, UUID affiliationId) {
        AffiliationRequest ar = loadPending(affiliationId);
        validateInitiator(userId, ar);

        ar.cancel();
        AffiliationRequest saved = affiliationRepository.save(ar);

        log.info("[Affiliation] cancelled — id={}", affiliationId);
        return AffiliationRequestResponse.fromEntity(saved);
    }

    private AffiliationRequest loadPending(UUID affiliationId) {
        AffiliationRequest ar = affiliationRepository.findByIdEager(affiliationId)
                .orElseThrow(() -> new AffiliationNotFoundException(affiliationId));

        if (ar.getStatus() != AffiliationStatus.PENDING) {
            throw new AffiliationAccessDeniedException(
                    "Affiliation request is already " + ar.getStatus().name().toLowerCase() + ".");
        }
        return ar;
    }

    private AffiliationRequest loadAndValidateCounterpart(UUID userId, UUID affiliationId) {
        AffiliationRequest ar = loadPending(affiliationId);

        if (ar.getType() == AffiliationType.REQUEST) {
            if (!ar.getEstablishment().getOwner().getId().equals(userId)) {
                throw new AffiliationAccessDeniedException(
                        "Only the establishment owner can accept or reject this request.");
            }
        } else {
            if (!ar.getProfessional().getId().equals(userId)) {
                throw new AffiliationAccessDeniedException(
                        "Only the invited professional can accept or reject this invitation.");
            }
        }
        return ar;
    }

    private void validateInitiator(UUID userId, AffiliationRequest ar) {
        if (ar.getType() == AffiliationType.REQUEST) {
            if (!ar.getProfessional().getId().equals(userId)) {
                throw new AffiliationAccessDeniedException(
                        "Only the requesting professional can cancel this request.");
            }
        } else {
            if (!ar.getEstablishment().getOwner().getId().equals(userId)) {
                throw new AffiliationAccessDeniedException(
                        "Only the establishment owner can cancel this invitation.");
            }
        }
    }
}
