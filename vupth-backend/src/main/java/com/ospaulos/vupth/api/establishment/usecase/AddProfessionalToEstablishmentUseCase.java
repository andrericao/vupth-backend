package com.ospaulos.vupth.api.establishment.usecase;

import com.ospaulos.vupth.api.professional.dto.response.ProfessionalQuickCreateResponse;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.service.EstablishmentOpenStatusService;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalAlreadyAffiliatedException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Owner adiciona um profissional existente ao seu establishment.
 *
 * Fluxo:
 *  1. Valida que o establishment existe e pertence ao owner autenticado.
 *  2. Valida que o profissional existe.
 *  3. Valida que o profissional não está já vinculado a outro establishment.
 *  4. Vincula e salva.
 *
 * HTTP: POST /establishment/{establishmentId}/professionals
 * Auth: ROLE_OWNER
 *
 * Responses:
 *   201 Created                        — profissional vinculado com sucesso
 *   401 Unauthorized                   — sem token
 *   403 ESTABLISHMENT_ACCESS_DENIED    — establishment não pertence ao owner autenticado
 *   404 ESTABLISHMENT_NOT_FOUND        — establishment não existe
 *   404 PROFESSIONAL_NOT_FOUND         — profissional não existe
 *   409 PROFESSIONAL_ALREADY_AFFILIATED — profissional já está vinculado a outro establishment
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AddProfessionalToEstablishmentUseCase {

    private final EstablishmentRepository establishmentRepository;
    private final ProfessionalRepository  professionalRepository;
    private final EstablishmentOpenStatusService openStatusService;

    @Transactional
    public ProfessionalQuickCreateResponse execute(UUID ownerId, UUID establishmentId, UUID professionalId) {

        log.info("[AddProfessional] execute — owner={} establishment={} professional={}",
                ownerId, establishmentId, professionalId);

        // 1. Load establishment
        Establishment establishment = establishmentRepository.findByIdEager(establishmentId)
                .orElseThrow(() -> new EstablishmentNotFoundException(establishmentId));

        // 2. Validate ownership — owner must own this establishment
        if (establishment.getOwner() == null ||
                !establishment.getOwner().getId().equals(ownerId)) {
            log.warn("[AddProfessional] owner={} does not own establishment={}", ownerId, establishmentId);
            throw new EstablishmentAccessDeniedException(
                    "You can only add professionals to your own establishments."
            );
        }

        // 3. Load professional
        Professional professional = professionalRepository.findByIdWithBaseUser(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        // 4. MVP rule: professional can only belong to one establishment
        if (professional.getEstablishment() != null) {
            log.warn("[AddProfessional] professional={} already affiliated to establishment={}",
                    professionalId, professional.getEstablishment().getId());
            throw new ProfessionalAlreadyAffiliatedException(
                    professionalId, professional.getEstablishment().getId()
            );
        }

        // 5. Link and save
        professional.setEstablishment(establishment);
        professionalRepository.save(professional);

        // S1 fatia 2 — quem chega WORKING/ON_BREAK pode abrir a loja (recalculo atomico).
        // Chegada OFF nao muda a presenca -> nao recalcula (preserva override manual do hibrido).
        if (professional.getWorkStatus() != null && professional.getWorkStatus().countsAsOpen()) {
            openStatusService.deriveFromProfessionals(establishmentId);
        }

        String profName = professional.getBaseUser() != null
                ? professional.getBaseUser().getFirstName()
                : professionalId.toString();

        log.info("[AddProfessional] professional={} ({}) linked to establishment={} ({})",
                professionalId, profName, establishmentId, establishment.getName());

        return new ProfessionalQuickCreateResponse(
                professional.getId(),
                profName,
                professional.getSpecialty(),
                professional.getBaseUser() != null ? professional.getBaseUser().getNickname() : null,
                establishment.getId(),
                establishment.getName(),
                professional.getIsAvailable(),
                professional.getWorkStatus(),
                professional.getAcceptingNew(),
                professional.getOperationMode(),
                "Professional successfully added to establishment."
        );
    }
}
