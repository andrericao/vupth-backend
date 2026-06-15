package com.ospaulos.vupth.api.affiliation.usecase;

import com.ospaulos.vupth.api.affiliation.dto.response.AffiliationRequestResponse;
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
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RequestAffiliationUseCase {

    private final ProfessionalRepository professionalRepository;
    private final EstablishmentRepository establishmentRepository;
    private final AffiliationRequestRepository affiliationRepository;

    @Transactional
    public AffiliationRequestResponse execute(UUID professionalId, UUID establishmentId) {
        log.info("[Affiliation] request — professional={} establishment={}", professionalId, establishmentId);

        Professional professional = professionalRepository.findByIdWithBaseUser(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        if (professional.getEstablishment() != null) {
            throw new ProfessionalAlreadyAffiliatedException(
                    professionalId, professional.getEstablishment().getId());
        }

        Establishment establishment = establishmentRepository.findByIdEager(establishmentId)
                .orElseThrow(() -> new EstablishmentNotFoundException(establishmentId));

        // Pendencia unica global: profissional com qualquer solicitacao (REQUEST) PENDING
        // nao pode solicitar a nenhum establishment ate resolver/cancelar a atual.
        if (affiliationRepository.existsByProfessional_IdAndTypeAndStatus(
                professionalId, AffiliationType.REQUEST, AffiliationStatus.PENDING)) {
            throw new AffiliationAlreadyPendingException(professionalId);
        }

        AffiliationRequest request = new AffiliationRequest(professional, establishment, AffiliationType.REQUEST);
        AffiliationRequest saved = affiliationRepository.save(request);

        log.info("[Affiliation] request created — id={}", saved.getId());
        return AffiliationRequestResponse.fromEntity(saved);
    }
}
