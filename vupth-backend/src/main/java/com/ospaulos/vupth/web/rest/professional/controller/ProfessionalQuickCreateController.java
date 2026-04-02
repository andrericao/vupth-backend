package com.ospaulos.vupth.web.rest.professional.controller;

import com.ospaulos.vupth.api.professional.dto.request.ProfessionalQuickCreateRequest;
import com.ospaulos.vupth.api.professional.dto.response.ProfessionalQuickCreateResponse;
import com.ospaulos.vupth.api.professional.usecase.QuickCreateProfessionalUseCase;
import com.ospaulos.vupth.api.serviceItem.dto.response.ServiceItemResponse;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("professional")
public class ProfessionalQuickCreateController {

    private final QuickCreateProfessionalUseCase useCase;
    private final ProfessionalRepository professionalRepository;
    private final ServiceItemRepository serviceItemRepository;

    @PostMapping("/quick-create")
    public ResponseEntity<ProfessionalQuickCreateResponse> quickCreate(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestBody @Valid ProfessionalQuickCreateRequest request) {

        log.info("POST /professional/quick-create — user={} name={} estab={}",
                principal.getUserId(), request.name(), request.establishmentId());

        ProfessionalQuickCreateResponse response = useCase.execute(principal.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /professional/{professionalId}/service-items
     *
     * Lists all service items offered by a specific professional.
     * Public endpoint — clients use this to choose services when booking.
     */
    @GetMapping("/{professionalId}/service-items")
    public ResponseEntity<List<ServiceItemResponse>> listServiceItemsByProfessional(
            @PathVariable UUID professionalId) {

        log.info("GET /professional/{}/service-items", professionalId);

        // Validate professional exists first — returns 404 if not found
        professionalRepository.findById(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        List<ServiceItemResponse> items = serviceItemRepository
                .findByProfessionalId(professionalId)
                .stream()
                .map(ServiceItemResponse::fromEntity)
                .toList();

        return ResponseEntity.ok(items);
    }
}
