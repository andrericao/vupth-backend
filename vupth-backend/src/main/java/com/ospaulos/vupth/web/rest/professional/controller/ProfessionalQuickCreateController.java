package com.ospaulos.vupth.web.rest.professional.controller;

import com.ospaulos.vupth.api.professional.dto.request.AffiliateToEstablishmentRequest;
import com.ospaulos.vupth.api.professional.dto.request.ProfessionalQuickCreateRequest;
import com.ospaulos.vupth.api.professional.dto.request.ProfessionalRegisterRequest;
import com.ospaulos.vupth.api.professional.dto.response.ProfessionalQuickCreateResponse;
import com.ospaulos.vupth.api.professional.usecase.AffiliateToEstablishmentUseCase;
import com.ospaulos.vupth.api.professional.usecase.LeaveEstablishmentUseCase;
import com.ospaulos.vupth.api.professional.usecase.QuickCreateProfessionalUseCase;
import com.ospaulos.vupth.api.professional.usecase.RegisterProfessionalUseCase;
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

/**
 * Controller para operações de criação e afiliação de profissionais.
 *
 * Endpoints:
 *
 *   POST /professional/register
 *     Cria perfil profissional SEM establishment (fluxo app parceiro: "sou profissional").
 *     Usuário fica disponível para ser adicionado por um owner.
 *     Auth: ROLE_CLIENT (qualquer autenticado)
 *
 *   POST /professional/quick-create
 *     Cria perfil profissional COM establishment (fluxo solo shop: owner que também trabalha).
 *     Auth: ROLE_CLIENT
 *
 *   POST /professional/affiliate
 *     Afilia profissional existente a um establishment (quando o profissional tem o ID).
 *     Auth: ROLE_PROFESSIONAL
 *
 *   DELETE /professional/me/establishment
 *     Profissional sai do establishment atual (bloqueia se houver atendimentos ativos).
 *     Auth: ROLE_PROFESSIONAL
 *
 *   GET /professional/{id}/service-items
 *     Lista serviços do profissional (público).
 *
 * @since v5
 * @compat register endpoint added v14
 * @compat affiliate endpoint added v14
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("professional")
public class ProfessionalQuickCreateController {

    private final QuickCreateProfessionalUseCase quickCreateUseCase;
    private final RegisterProfessionalUseCase    registerUseCase;
    private final AffiliateToEstablishmentUseCase affiliateUseCase;
    private final LeaveEstablishmentUseCase       leaveUseCase;
    private final ProfessionalRepository         professionalRepository;
    private final ServiceItemRepository          serviceItemRepository;

    /**
     * GET /professional/me
     *
     * Retorna os dados do profissional autenticado.
     * Auth: ROLE_PROFESSIONAL
     */
    @GetMapping("/me")
    public ResponseEntity<ProfessionalQuickCreateResponse> getMyProfile(
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("GET /professional/me — user={}", principal.getUserId());

        Professional professional = professionalRepository.findByIdWithBaseUser(principal.getUserId())
                .orElseThrow(() -> new ProfessionalNotFoundException(principal.getUserId()));

        return ResponseEntity.ok(new ProfessionalQuickCreateResponse(
                professional.getId(),
                professional.getBaseUser().getFirstName(),
                professional.getSpecialty(),
                professional.getEstablishment() != null ? professional.getEstablishment().getId() : null,
                professional.getEstablishment() != null ? professional.getEstablishment().getName() : null,
                professional.getIsAvailable(),
                null
        ));
    }

    /**
     * POST /professional/register
     *
     * Fluxo app parceiro: usuário escolhe "sou profissional" sem criar uma loja.
     * Cria o perfil PROFESSIONAL com establishment = null.
     * O owner da loja deve vincular o profissional via
     *   POST /establishment/{id}/professionals
     *
     * Auth: ROLE_CLIENT (qualquer autenticado)
     */
    @PostMapping("/register")
    public ResponseEntity<ProfessionalQuickCreateResponse> register(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestBody @Valid ProfessionalRegisterRequest request) {

        log.info("POST /professional/register — user={} name={}",
                principal.getUserId(), request.name());

        ProfessionalQuickCreateResponse response =
                registerUseCase.execute(principal.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * POST /professional/quick-create
     *
     * Fluxo solo shop: owner que também trabalha na própria loja.
     * Cria o perfil PROFESSIONAL já vinculado ao establishment.
     * Auth: ROLE_CLIENT
     */
    @PostMapping("/quick-create")
    public ResponseEntity<ProfessionalQuickCreateResponse> quickCreate(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestBody @Valid ProfessionalQuickCreateRequest request) {

        log.info("POST /professional/quick-create — user={} name={} estab={}",
                principal.getUserId(), request.name(), request.establishmentId());

        ProfessionalQuickCreateResponse response =
                quickCreateUseCase.execute(principal.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * POST /professional/affiliate
     *
     * Profissional já registrado (sem establishment) se afilia a um establishment
     * usando o establishmentId.
     * Regra MVP: um profissional só pode estar vinculado a um establishment por vez.
     * Auth: ROLE_PROFESSIONAL
     */
    @PostMapping("/affiliate")
    public ResponseEntity<ProfessionalQuickCreateResponse> affiliateToEstablishment(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestBody @Valid AffiliateToEstablishmentRequest request) {

        log.info("POST /professional/affiliate — user={} establishment={}",
                principal.getUserId(), request.establishmentId());

        ProfessionalQuickCreateResponse response =
                affiliateUseCase.execute(principal.getUserId(), request.establishmentId());
        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /professional/me/establishment
     *
     * Profissional sai do establishment atual por conta própria.
     * Bloqueia se houver atendimentos ativos (SCHEDULED ou IN_PROGRESS).
     * Auth: ROLE_PROFESSIONAL
     */
    @DeleteMapping("/me/establishment")
    public ResponseEntity<Void> leaveEstablishment(
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("DELETE /professional/me/establishment — user={}", principal.getUserId());

        leaveUseCase.execute(principal.getUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /professional/{professionalId}/service-items
     *
     * Lista serviços oferecidos por um profissional.
     * Público — clientes usam para escolher serviços ao entrar na fila.
     */
    @GetMapping("/{professionalId}/service-items")
    public ResponseEntity<List<ServiceItemResponse>> listServiceItemsByProfessional(
            @PathVariable UUID professionalId) {

        log.info("GET /professional/{}/service-items", professionalId);

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
