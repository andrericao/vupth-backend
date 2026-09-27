package com.ospaulos.vupth.api.establishment.usecase;

import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentProfessionalResponse;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /establishment/{id}/professionals} — todos os profissionais vinculados, inclusive sem
 * servico (Partner S8). Visao de gestao: so o dono ou um profissional da propria loja (403 para os
 * demais). Ordem: quem tem servico primeiro, depois por nome.
 */
@Service
@RequiredArgsConstructor
public class ListEstablishmentProfessionalsUseCase {

    private final EstablishmentRepository establishmentRepository;
    private final ProfessionalRepository professionalRepository;

    @Transactional(readOnly = true)
    public List<EstablishmentProfessionalResponse> execute(UUID establishmentId, UUID requesterId) {
        Establishment establishment = establishmentRepository.findById(establishmentId)
                .orElseThrow(() -> new EstablishmentNotFoundException(establishmentId));
        List<Professional> professionals = professionalRepository.findByEstablishmentId(establishmentId);

        boolean isOwner = establishment.getOwner() != null && establishment.getOwner().getId().equals(requesterId);
        boolean isMember = professionals.stream().anyMatch(p -> p.getId().equals(requesterId));
        if (!isOwner && !isMember) {
            throw new EstablishmentAccessDeniedException(requesterId, establishmentId);
        }

        return professionals.stream()
                .map(EstablishmentProfessionalResponse::fromEntity)
                .sorted(Comparator.comparing(EstablishmentProfessionalResponse::hasService).reversed()
                        .thenComparing(r -> r.name() == null ? "" : r.name(), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}
