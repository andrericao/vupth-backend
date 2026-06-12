package com.ospaulos.vupth.web.rest.establishment.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.ospaulos.vupth.api.establishment.dto.request.AddProfessionalToEstablishmentRequest;
import com.ospaulos.vupth.api.establishment.dto.request.EstablishmentGeneralInfosRequest;
import com.ospaulos.vupth.api.establishment.dto.request.EstablishmentRequest;
import com.ospaulos.vupth.api.establishment.dto.request.TransferOwnershipRequest;
import com.ospaulos.vupth.api.establishment.dto.request.UpdateOpenStatusRequest;
import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentGeneralInfosResponse;
import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentResponse;
import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentWithProfessionalsResponse;
import com.ospaulos.vupth.api.establishment.usecase.AddProfessionalToEstablishmentUseCase;
import com.ospaulos.vupth.api.establishment.usecase.GetEstablishmentWithQueueUseCase;
import com.ospaulos.vupth.api.establishment.usecase.RemoveProfessionalFromEstablishmentUseCase;
import com.ospaulos.vupth.api.establishment.usecase.TransferEstablishmentOwnershipUseCase;
import com.ospaulos.vupth.api.establishment.usecase.UpdateEstablishmentGeneralInfosUseCase;
import com.ospaulos.vupth.api.professional.dto.response.ProfessionalQuickCreateResponse;
import com.ospaulos.vupth.api.serviceItem.dto.request.ServiceItemRequest;
import com.ospaulos.vupth.api.serviceItem.dto.response.ServiceItemResponse;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.service.EstablishmentService;
import com.ospaulos.vupth.domain.establishment.service.impl.EstablishmentServiceImpl;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
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

import java.util.List;
import java.util.UUID;

/**
 * Establishment REST controller.
 *
 * Endpoints:
 *   POST   /establishment/create                          — cria establishment completo (OWNER)
 *   GET    /establishment                                 — lista ativos (público, suporta ?name= e ?serviceType=)
 *   GET    /establishment/id/{id}                        — busca por ID (público)
 *   GET    /establishment/id/{id}/professionals-with-queue — snapshot da fila (público)
 *   PATCH  /establishment/{id}/open-status               — atualiza status de abertura (OWNER)
 *   POST   /establishment/{establishmentId}/service-items — adiciona serviço (OWNER)
 *   POST   /establishment/{establishmentId}/professionals — owner adiciona profissional (OWNER)
 *   DELETE /establishment/{id}/professionals/{pid}       — owner remove profissional (OWNER)
 *   PATCH  /establishment/{id}/owner                    — transfere ownership (OWNER)
 *
 * @since v5
 * @compat service-items endpoint fixed v8
 * @compat filter/search + updateOpenStatus added v11
 * @compat addProfessional endpoint added v14
 */
@Slf4j
@AllArgsConstructor
@RestController
@RequestMapping("establishment")
public class EstablishmentController {

    private final EstablishmentServiceImpl            service;
    private final EstablishmentRepository             establishmentRepository;
    private final GetEstablishmentWithQueueUseCase    getWithQueueUseCase;
    private final ProfessionalServiceImpl             professionalService;
    private final AddProfessionalToEstablishmentUseCase addProfessionalUseCase;
    private final UpdateEstablishmentGeneralInfosUseCase updateGeneralInfosUseCase;
    private final TransferEstablishmentOwnershipUseCase transferOwnershipUseCase;
    private final RemoveProfessionalFromEstablishmentUseCase removeProfessionalUseCase;

    @GetMapping("/mine")
    public ResponseEntity<List<EstablishmentResponse>> findMyEstablishments(
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Establishment] findMyEstablishments — owner={}", user.getUserId());

        List<EstablishmentResponse> result = establishmentRepository
                .findByOwnerId(user.getUserId())
                .stream()
                .map(EstablishmentResponse::fromEntity)
                .toList();

        return ResponseEntity.ok(result);
    }

    @PostMapping("/create")
    public ResponseEntity<EstablishmentResponse> createEstablishment(
            @RequestBody @Valid EstablishmentRequest request) throws JsonProcessingException {
        log.info("[Establishment] createEstablishment — name={}", request.name());
        EstablishmentResponse establishment = service.registerEstablishment(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(establishment);
    }

    /**
     * GET /establishment
     * Lista establishments ativos. Filtros opcionais:
     *   ?name=barbearia     — busca parcial, case-insensitive
     *   ?serviceType=BARBER — filtra por categoria
     */
    @GetMapping
    public ResponseEntity<List<EstablishmentResponse>> findAll(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) ServiceType serviceType) {

        log.info("[Establishment] findAll — name={} serviceType={}", name, serviceType);

        List<EstablishmentResponse> result;
        if (name != null && !name.isBlank()) {
            result = service.searchByName(name);
        } else if (serviceType != null) {
            result = service.findByServiceType(serviceType);
        } else {
            result = service.findAllActive();
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("id/{id}")
    public ResponseEntity<EstablishmentResponse> findById(@PathVariable UUID id) {
        log.info("[Establishment] findById — id={}", id);
        EstablishmentResponse establishment = service.findById(id);
        return ResponseEntity.status(HttpStatus.OK).body(establishment);
    }

    /**
     * GET /establishment/id/{id}/professionals-with-queue
     * Retorna o establishment com todos os profissionais e suas filas ao vivo.
     * Endpoint público.
     */
    @GetMapping("id/{id}/professionals-with-queue")
    public ResponseEntity<EstablishmentWithProfessionalsResponse> findWithQueue(
            @PathVariable UUID id) {
        log.info("[Establishment] findWithQueue — id={}", id);
        EstablishmentWithProfessionalsResponse response = getWithQueueUseCase.execute(id);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/general-infos")
    public ResponseEntity<EstablishmentGeneralInfosResponse> updateGeneralInfos(
            @PathVariable UUID id,
            @RequestBody @Valid EstablishmentGeneralInfosRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Establishment] updateGeneralInfos — id={} owner={}", id, user.getUserId());

        EstablishmentGeneralInfosResponse response =
                updateGeneralInfosUseCase.execute(user.getUserId(), id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /establishment/{id}/open-status
     * Owner atualiza o status de abertura do establishment.
     * Valores válidos: OPEN, CLOSED, TEMPORARILY_CLOSED
     * Requer: ROLE_OWNER.
     */
    @PatchMapping("/{id}/open-status")
    public ResponseEntity<EstablishmentResponse> updateOpenStatus(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateOpenStatusRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Establishment] updateOpenStatus — id={} owner={} status={}",
                id, user.getUserId(), request.openStatus());

        EstablishmentResponse response =
                service.updateOpenStatus(id, user.getUserId(), request.openStatus());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{establishmentId}/service-items")
    public ResponseEntity<ServiceItemResponse> addServiceItem(
            @PathVariable UUID establishmentId,
            @RequestBody @Valid ServiceItemRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Establishment] addServiceItem — establishment={} owner={}",
                establishmentId, user.getUserId());

        ServiceItemResponse response = professionalService.createServiceItemForEstablishment(
                user.getUserId(), establishmentId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * POST /establishment/{establishmentId}/professionals
     *
     * Owner adiciona um profissional existente ao seu establishment.
     * O profissional deve ter se registrado via POST /professional/register
     * (perfil criado sem establishment) para estar disponível para vinculação.
     *
     * Regra MVP: profissional só pode estar em um establishment por vez.
     *
     * @param establishmentId  ID do establishment do owner
     * @param request          { "professionalId": UUID }
     * @param user             owner autenticado (extraído do JWT)
     *
     * Requer: ROLE_OWNER.
     */
    @PostMapping("/{establishmentId}/professionals")
    public ResponseEntity<ProfessionalQuickCreateResponse> addProfessional(
            @PathVariable UUID establishmentId,
            @RequestBody @Valid AddProfessionalToEstablishmentRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Establishment] addProfessional — establishment={} owner={} professional={}",
                establishmentId, user.getUserId(), request.professionalId());

        ProfessionalQuickCreateResponse response =
                addProfessionalUseCase.execute(user.getUserId(), establishmentId, request.professionalId());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{establishmentId}/professionals/{professionalId}")
    public ResponseEntity<Void> removeProfessional(
            @PathVariable UUID establishmentId,
            @PathVariable UUID professionalId,
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Establishment] removeProfessional — establishment={} owner={} professional={}",
                establishmentId, user.getUserId(), professionalId);

        removeProfessionalUseCase.execute(user.getUserId(), establishmentId, professionalId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/owner")
    public ResponseEntity<EstablishmentResponse> transferOwnership(
            @PathVariable UUID id,
            @RequestBody @Valid TransferOwnershipRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Establishment] transferOwnership — id={} from={} to={}",
                id, user.getUserId(), request.newOwnerId());

        EstablishmentResponse response =
                transferOwnershipUseCase.execute(user.getUserId(), id, request.newOwnerId());
        return ResponseEntity.ok(response);
    }
}
