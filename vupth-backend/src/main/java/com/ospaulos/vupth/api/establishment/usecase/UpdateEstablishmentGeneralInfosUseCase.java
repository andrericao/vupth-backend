package com.ospaulos.vupth.api.establishment.usecase;

import com.ospaulos.vupth.api.establishment.dto.request.EstablishmentGeneralInfosRequest;
import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentGeneralInfosResponse;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class UpdateEstablishmentGeneralInfosUseCase {

    private final EstablishmentRepository repository;

    public EstablishmentGeneralInfosResponse execute(UUID ownerId, UUID establishmentId, EstablishmentGeneralInfosRequest request) {
        log.info("[Establishment] updateGeneralInfos — id={} owner={}", establishmentId, ownerId);

        Establishment establishment = repository.findByIdAndOwnerId(establishmentId, ownerId)
                .orElseThrow(() -> new EstablishmentNotFoundException(establishmentId));

        List<String> modifiedFields = updateIfPresent(establishment, request);

        if (modifiedFields.isEmpty()) {
            log.info("No fields updated for establishment id={}", establishmentId);
        } else {
            log.info("Updated fields {} for establishment id={}", modifiedFields, establishmentId);
            repository.save(establishment);
        }

        return EstablishmentGeneralInfosResponse.fromEntity(establishment, modifiedFields);
    }

    private List<String> updateIfPresent(Establishment establishment, EstablishmentGeneralInfosRequest request) {
        List<String> modified = new ArrayList<>();

        if (request.name() != null && !request.name().isBlank()) {
            establishment.setName(request.name());
            modified.add("name");
        }

        if (request.urlImage() != null && !request.urlImage().isBlank()) {
            establishment.setUrlImage(request.urlImage());
            modified.add("url_image");
        }

        if (request.serviceType() != null) {
            establishment.setServiceType(request.serviceType());
            modified.add("service_type");
        }

        if (request.services() != null && !request.services().isEmpty()) {
            establishment.setServices(Set.copyOf(request.services()));
            modified.add("services");
        }

        return modified;
    }
}
