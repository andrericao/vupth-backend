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
import com.ospaulos.vupth.api.report.dto.response.EstablishmentReportResponse;
import com.ospaulos.vupth.api.serviceItem.dto.request.ServiceItemRequest;
import com.ospaulos.vupth.api.serviceItem.dto.request.UpdateServiceItemRequest;
import com.ospaulos.vupth.api.serviceItem.dto.response.ServiceItemResponse;
import com.ospaulos.vupth.domain.report.service.ReportService;
import com.ospaulos.vupth.domain.serviceItem.service.ServiceItemService;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Establishment REST controller.
 *
 * Endpoints:
 *   POST   /establishment/create                          — cria establishment completo (OWNER)
 *   GET    /establishment                                 — lista ativos (público, suporta ?name=, ?neighborhood= e ?serviceType=)
 *   GET    /establishment/id/{id}                        — busca por ID (público)
 *   GET    /establishment/id/{id}/professionals-with-queue — snapshot da fila (público)
 *   PATCH  /establishment/{id}/open-status               — atualiza status de abertura (OWNER ou profissional afiliado)
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
    private final ReportService                          reportService;
    private final ServiceItemService                     serviceItemService;

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
     *   ?name=barbearia                      — busca parcial por nome, case-insensitive (tem prioridade)
     *   ?serviceType=BARBER&neighborhood=centro — combina categoria + bairro ("barbearia no Centro")
     *   ?neighborhood=centro                 — so bairro
     *   ?serviceType=BARBER                  — so categoria
     * Sem filtro, lista todos os ativos. Ordenacao sempre por createdAt DESC.
     */
    @GetMapping
    public ResponseEntity<List<EstablishmentResponse>> findAll(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String neighborhood,
            @RequestParam(required = false) ServiceType serviceType,
            @RequestParam(required = false, defaultValue = "false") boolean includeAll) {

        log.info("[Establishment] findAll — name={} neighborhood={} serviceType={} includeAll={}",
                name, neighborhood, serviceType, includeAll);

        boolean hasNeighborhood = neighborhood != null && !neighborhood.isBlank();

        List<EstablishmentResponse> result;
        if (name != null && !name.isBlank()) {
            result = service.searchByName(name);
        } else if (serviceType != null && hasNeighborhood) {
            result = service.searchByServiceTypeAndNeighborhood(serviceType, neighborhood);
        } else if (hasNeighborhood) {
            result = service.searchByNeighborhood(neighborhood);
        } else if (serviceType != null) {
            result = service.findByServiceType(serviceType);
        } else {
            result = service.findAllActive();
        }

        // BUG5: por padrão a listagem pública só devolve lojas ABERTAS (OPEN).
        // ?includeAll=true devolve todas as ativas (inclui CLOSED/TEMPORARILY_CLOSED),
        // p/ o Front-Partner/gestão. Filtro pós-fetch: preserva a ordenação por tempo de espera.
        if (!includeAll) {
            result = result.stream()
                    .filter(e -> OpenStatus.OPEN.name().equals(e.openStatus()))
                    .toList();
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
     * Dono da loja OU profissional afiliado a ela atualiza o status de abertura.
     * Valores válidos: OPEN, CLOSED, TEMPORARILY_CLOSED
     * Requer: ROLE_OWNER ou ROLE_PROFESSIONAL (vínculo verificado no serviço → 403 se não pertence).
     */
    @PatchMapping("/{id}/open-status")
    public ResponseEntity<EstablishmentResponse> updateOpenStatus(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateOpenStatusRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Establishment] updateOpenStatus — id={} requester={} status={}",
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
     * PUT /establishment/{establishmentId}/service-items/{itemId}
     *
     * Edita um servico do establishment (parcial — campos null/branco ignorados).
     * Autorizado ao OWNER da loja OU ao proprio profissional dono do item.
     * 404 se a loja/item nao existir ou o item nao pertencer a esta loja;
     * 403 se o requester nao for owner nem dono do item; 409 se o novo nome colidir.
     */
    @PutMapping("/{establishmentId}/service-items/{itemId}")
    public ResponseEntity<ServiceItemResponse> updateServiceItem(
            @PathVariable UUID establishmentId,
            @PathVariable UUID itemId,
            @RequestBody @Valid UpdateServiceItemRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Establishment] updateServiceItem — establishment={} item={} requester={}",
                establishmentId, itemId, user.getUserId());

        ServiceItemResponse response = serviceItemService.updateServiceItemForEstablishment(
                user.getUserId(), establishmentId, itemId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /establishment/{establishmentId}/service-items/{itemId}
     *
     * Remove um servico do establishment.
     * Autorizado ao OWNER da loja OU ao proprio profissional dono do item.
     */
    @DeleteMapping("/{establishmentId}/service-items/{itemId}")
    public ResponseEntity<Void> deleteServiceItem(
            @PathVariable UUID establishmentId,
            @PathVariable UUID itemId,
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Establishment] deleteServiceItem — establishment={} item={} requester={}",
                establishmentId, itemId, user.getUserId());

        serviceItemService.deleteServiceItemForEstablishment(user.getUserId(), establishmentId, itemId);
        return ResponseEntity.noContent().build();
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

    /**
     * GET /establishment/{id}/reports?dateFrom=&dateTo=
     *
     * Relatorio agregado do establishment (#4 + #5): totais do periodo
     * (atendimentos FINALIZADOS) + breakdown de receita por profissional.
     * Periodo opcional (dateFrom/dateTo em ISO yyyy-MM-dd, ambos inclusivos);
     * sem periodo = desde sempre. Apenas o owner.
     *
     * Requer: ROLE_OWNER.
     */
    @GetMapping("/{id}/reports")
    public ResponseEntity<EstablishmentReportResponse> getReports(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Establishment] getReports — id={} owner={} dateFrom={} dateTo={}",
                id, user.getUserId(), dateFrom, dateTo);

        EstablishmentReportResponse response =
                reportService.getEstablishmentReport(user.getUserId(), id, dateFrom, dateTo);
        return ResponseEntity.ok(response);
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
