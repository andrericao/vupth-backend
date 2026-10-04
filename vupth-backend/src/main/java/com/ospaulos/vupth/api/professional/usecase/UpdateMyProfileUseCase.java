package com.ospaulos.vupth.api.professional.usecase;

import com.ospaulos.vupth.api.professional.dto.request.UpdateProfessionalRequest;
import com.ospaulos.vupth.api.professional.dto.response.ProfessionalQuickCreateResponse;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Atualizacao parcial do proprio perfil profissional.
 *
 * - name      → firstName do BaseUser
 * - specialty → specialty do Professional
 * - contact_phone → contato exposto ao cliente (em branco deixa de expor)
 *
 * Campos null/em branco sao ignorados. Para specialty, a string e trimada
 * (em branco vira null para limpar a especialidade).
 *
 * HTTP: PATCH /professional/me
 * Auth: ROLE_PROFESSIONAL
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpdateMyProfileUseCase {

    private final ProfessionalRepository professionalRepository;

    @Transactional
    public ProfessionalQuickCreateResponse execute(UUID userId, UpdateProfessionalRequest request) {

        log.info("[UpdateMyProfile] execute — user={} name={} specialty={}",
                userId, request.name(), request.specialty());

        Professional professional = professionalRepository
                .findByIdWithBaseUserAndEstablishment(userId)
                .orElseThrow(() -> new ProfessionalNotFoundException(userId));

        if (request.name() != null && !request.name().isBlank()) {
            professional.getBaseUser().setFirstName(request.name().trim());
        }

        if (request.specialty() != null) {
            String trimmed = request.specialty().trim();
            professional.setSpecialty(trimmed.isBlank() ? null : trimmed);
        }

        if (request.contactPhone() != null) {
            String trimmed = request.contactPhone().trim();
            professional.setContactPhone(trimmed.isBlank() ? null : trimmed);
        }

        professionalRepository.save(professional);

        log.info("[UpdateMyProfile] profile updated — id={} name={} specialty={}",
                professional.getId(), professional.getBaseUser().getFirstName(), professional.getSpecialty());

        return new ProfessionalQuickCreateResponse(
                professional.getId(),
                professional.getBaseUser().getFirstName(),
                professional.getSpecialty(),
                professional.getBaseUser().getNickname(),
                professional.getEstablishment() != null ? professional.getEstablishment().getId() : null,
                professional.getEstablishment() != null ? professional.getEstablishment().getName() : null,
                professional.getIsAvailable(),
                professional.getWorkStatus(),
                professional.getAcceptingNew(),
                professional.getOperationMode(),
                null,
                professional.getContactPhone()
        );
    }
}
