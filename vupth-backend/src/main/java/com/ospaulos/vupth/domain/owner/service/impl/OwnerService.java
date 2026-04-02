package com.ospaulos.vupth.domain.owner.service.impl;

import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
@AllArgsConstructor
public class OwnerService {

    private final EstablishmentRepository establishmentRepository;

    public boolean addProfessional(UUID establishmentId, Professional professional) {

        log.info("OWNER SERVICE ADD PROFESSIONAL");

        Establishment establishment = establishmentRepository.findById(establishmentId)
                .orElseThrow(() -> new EntityNotFoundException("Establishment not found"));

        // Business rule: OWNER → PROFESSIONAL is allowed (e.g. solo worker in own establishment).
        // The reverse path (PROFESSIONAL → OWNER) is NOT allowed — enforced at registration time.
        return establishment.addProfessional(professional);
    }
}
