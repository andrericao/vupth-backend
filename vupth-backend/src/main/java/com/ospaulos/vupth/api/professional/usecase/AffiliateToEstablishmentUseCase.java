package com.ospaulos.vupth.api.professional.usecase;

import com.ospaulos.vupth.api.professional.dto.response.ProfessionalQuickCreateResponse;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalAlreadyAffiliatedException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Vincula um profissional já existente a um (novo) establishment.
 *
 * Fluxo:
 *  1. Valida que o usuário autenticado tem role PROFESSIONAL.
 *  2. Carrega o registro Professional do banco.
 *  3. Verifica que não há establishment já vinculado (regra MVP: um por vez).
 *  4. Valida que o establishment alvo existe.
 *  5. Seta o vínculo e salva.
 *
 * HTTP: POST /professional/affiliate
 * Auth: ROLE_PROFESSIONAL (via SecurityConfig)
 * Responses:
 *   200 OK                        — afiliado com sucesso
 *   401 Unauthorized              — sem token
 *   404 PROFESSIONAL_NOT_FOUND    — user não tem role PROFESSIONAL ou professional não encontrado
 *   404 ESTABLISHMENT_NOT_FOUND   — establishment alvo não existe
 *   409 PROFESSIONAL_ALREADY_AFFILIATED — profissional já possui um vínculo ativo
 *   422 VALIDATION_ERROR          — establishmentId nulo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AffiliateToEstablishmentUseCase {

    private final UserRepository          userRepository;
    private final ProfessionalRepository  professionalRepository;
    private final EstablishmentRepository establishmentRepository;

    @Transactional
    public ProfessionalQuickCreateResponse execute(UUID userId, UUID establishmentId) {

        log.info("[Affiliate] execute — user={} targetEstablishment={}", userId, establishmentId);

        // 1. Load user
        BaseUser user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        // 2. Must have PROFESSIONAL role
        if (!user.hasRole(RoleType.PROFESSIONAL)) {
            log.warn("[Affiliate] user={} does not have PROFESSIONAL role", userId);
            throw new ProfessionalNotFoundException(userId);
        }

        // 3. Load professional record
        Professional professional = professionalRepository.findById(userId)
                .orElseThrow(() -> new ProfessionalNotFoundException(userId));

        // 4. MVP rule: one establishment per professional
        if (professional.getEstablishment() != null) {
            log.warn("[Affiliate] professional={} already affiliated to establishment={}",
                    userId, professional.getEstablishment().getId());
            throw new ProfessionalAlreadyAffiliatedException(userId, professional.getEstablishment().getId());
        }

        // 5. Load target establishment
        Establishment establishment = establishmentRepository.findById(establishmentId)
                .orElseThrow(() -> new EstablishmentNotFoundException(establishmentId));

        // 6. Set and persist
        professional.setEstablishment(establishment);
        professionalRepository.save(professional);

        log.info("[Affiliate] professional={} affiliated to establishment={} ({})",
                userId, establishment.getId(), establishment.getName());

        return new ProfessionalQuickCreateResponse(
                professional.getId(),
                user.getFirstName(),
                professional.getSpecialty(),
                establishment.getId(),
                establishment.getName(),
                professional.getIsAvailable(),
                "Professional affiliated to establishment successfully."
        );
    }
}
