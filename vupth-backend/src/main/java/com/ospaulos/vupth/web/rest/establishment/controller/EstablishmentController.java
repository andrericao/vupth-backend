package com.ospaulos.vupth.web.rest.establishment.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.ospaulos.vupth.api.establishment.dto.request.EstablishmentRequest;
import com.ospaulos.vupth.api.establishment.dto.request.UpdateOpenStatusRequest;
import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentResponse;
import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentWithProfessionalsResponse;
import com.ospaulos.vupth.api.establishment.usecase.GetEstablishmentWithQueueUseCase;
import com.ospaulos.vupth.api.serviceItem.dto.request.ServiceItemRequest;
import com.ospaulos.vupth.api.serviceItem.dto.response.ServiceItemResponse;
import com.ospaulos.vupth.domain.establishment.service.EstablishmentOpenStatusService;
import com.ospaulos.vupth.domain.establishment.service.impl.EstablishmentServiceImpl;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import com.ospaulos.vupth.domain.professional.service.impl.ProfessionalServiceImpl;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Establishment REST controller.
 *
 * POST /{establishmentId}/service-items was using @PathVariable Professional which caused
 * a 500 because Spring cannot convert a UUID path variable into a Professional entity.
 * Fixed in v8: uses @AuthenticationPrincipal to extract the professionalId from the JWT
 * and delegates to ProfessionalServiceImpl.createServiceItem(professionalId, request).
 *
 * @since v5
 * @compat service-items endpoint fixed v8
 */
@Slf4j
@AllArgsConstructor
@RestController
@RequestMapping("establishment")
public class EstablishmentController {

    private final EstablishmentServiceImpl service;
    private final GetEstablishmentWithQueueUseCase getWithQueueUseCase;
    private final ProfessionalServiceImpl professionalService;
    private final EstablishmentOpenStatusService establishmentOpenStatusService;

    @PostMapping("/create")
    public ResponseEntity<EstablishmentResponse> createEstablishment(
            @RequestBody @Valid EstablishmentRequest request) throws JsonProcessingException {
        log.info("[Establishment] createEstablishment — name={}", request.name());
        EstablishmentResponse establishment = service.registerEstablishment(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(establishment);
    }

    /**
     * GET /establishment
     * Public endpoint — lists all establishments (client-app browse).
     */
    @GetMapping
    public ResponseEntity<?> findAll(@RequestParam(required = false) String serviceType) {
        log.info("[Establishment] findAll — serviceType={}", serviceType);
        if (serviceType != null && !serviceType.isEmpty()) {
            return ResponseEntity.ok(service.findByServiceType(ServiceType.valueOf(serviceType)));
        }
        return ResponseEntity.ok(service.findAll());
    }

    @GetMapping("id/{id}")
    public ResponseEntity<EstablishmentResponse> findById(@PathVariable UUID id) {
        log.info("[Establishment] findById — id={}", id);
        EstablishmentResponse establishment = service.findById(id);
        return ResponseEntity.status(HttpStatus.OK).body(establishment);
    }

    /**
     * GET /establishment/id/{id}/professionals-with-queue
     * Returns the establishment with all linked professionals and their live queues.
     * Public endpoint.
     */
    @GetMapping("id/{id}/professionals-with-queue")
    public ResponseEntity<EstablishmentWithProfessionalsResponse> findWithQueue(
            @PathVariable UUID id) {
        log.info("[Establishment] findWithQueue — id={}", id);
        EstablishmentWithProfessionalsResponse response = getWithQueueUseCase.execute(id);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /establishment/{establishmentId}/service-items
     *
     * Creates a service item for the professional identified by the JWT token.
     * The professionalId is extracted from @AuthenticationPrincipal — the caller
     * must be the professional who owns the establishment.
     *
     * @param establishmentId path variable (used for route clarity; ownership validated in service)
     * @param request         service item data
     * @param user            authenticated principal — professionalId = user.getUserId()
     */
    @PostMapping("/{establishmentId}/service-items")
    public ResponseEntity<ServiceItemResponse> addServiceItem(
            @PathVariable UUID establishmentId,
            @RequestBody @Valid ServiceItemRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Establishment] addServiceItem — establishment={} professional={}",
                establishmentId, user.getUserId());

        ServiceItemResponse response = professionalService.createServiceItem(user.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * PATCH /establishment/{id}/status
     * Toggles the open/closed status of an establishment.
     * Requires ROLE_OWNER.
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<Void> updateOpenStatus(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateOpenStatusRequest request) {

        log.info("[Establishment] updateOpenStatus — id={} newStatus={}", id, request.openStatus());
        establishmentOpenStatusService.updateEstablishmentOpenStatus(id, request.openStatus());
        return ResponseEntity.ok().build();
    }
}
