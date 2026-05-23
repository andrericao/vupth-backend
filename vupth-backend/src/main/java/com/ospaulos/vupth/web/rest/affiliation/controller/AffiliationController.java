package com.ospaulos.vupth.web.rest.affiliation.controller;

import com.ospaulos.vupth.api.affiliation.dto.request.CreateAffiliationInvitationDTO;
import com.ospaulos.vupth.api.affiliation.dto.request.CreateAffiliationRequestDTO;
import com.ospaulos.vupth.api.affiliation.dto.response.AffiliationRequestResponse;
import com.ospaulos.vupth.api.affiliation.usecase.InviteProfessionalUseCase;
import com.ospaulos.vupth.api.affiliation.usecase.RequestAffiliationUseCase;
import com.ospaulos.vupth.api.affiliation.usecase.ResolveAffiliationUseCase;
import com.ospaulos.vupth.domain.affiliation.model.AffiliationStatus;
import com.ospaulos.vupth.domain.affiliation.repository.AffiliationRequestRepository;
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
 * Controller para fluxo de afiliação bidirecional.
 *
 * Endpoints:
 *   POST   /professional/me/affiliations               — profissional solicita afiliação (PROFESSIONAL)
 *   GET    /professional/me/affiliations                — profissional lista pendentes (PROFESSIONAL)
 *   POST   /establishment/{id}/affiliations             — owner convida profissional (OWNER)
 *   GET    /establishment/{id}/affiliations              — owner lista pendentes (OWNER)
 *   PATCH  /affiliations/{id}/accept                    — contraparte aceita (PROFESSIONAL ou OWNER)
 *   PATCH  /affiliations/{id}/reject                    — contraparte rejeita (PROFESSIONAL ou OWNER)
 *   DELETE /affiliations/{id}                           — iniciador cancela (PROFESSIONAL ou OWNER)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AffiliationController {

    private final RequestAffiliationUseCase requestAffiliationUseCase;
    private final InviteProfessionalUseCase inviteProfessionalUseCase;
    private final ResolveAffiliationUseCase resolveAffiliationUseCase;
    private final AffiliationRequestRepository affiliationRepository;

    @PostMapping("/professional/me/affiliations")
    public ResponseEntity<AffiliationRequestResponse> requestAffiliation(
            @AuthenticationPrincipal UserDetailsImpl user,
            @RequestBody @Valid CreateAffiliationRequestDTO request) {

        log.info("[Affiliation] POST /professional/me/affiliations — user={} establishment={}",
                user.getUserId(), request.establishmentId());

        AffiliationRequestResponse response =
                requestAffiliationUseCase.execute(user.getUserId(), request.establishmentId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/professional/me/affiliations")
    public ResponseEntity<List<AffiliationRequestResponse>> listProfessionalAffiliations(
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Affiliation] GET /professional/me/affiliations — user={}", user.getUserId());

        List<AffiliationRequestResponse> response = affiliationRepository
                .findByProfessionalAndStatus(user.getUserId(), AffiliationStatus.PENDING)
                .stream()
                .map(AffiliationRequestResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/establishment/{establishmentId}/affiliations")
    public ResponseEntity<AffiliationRequestResponse> inviteProfessional(
            @PathVariable UUID establishmentId,
            @AuthenticationPrincipal UserDetailsImpl user,
            @RequestBody @Valid CreateAffiliationInvitationDTO request) {

        log.info("[Affiliation] POST /establishment/{}/affiliations — owner={} professional={}",
                establishmentId, user.getUserId(), request.professionalId());

        AffiliationRequestResponse response =
                inviteProfessionalUseCase.execute(user.getUserId(), establishmentId, request.professionalId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/establishment/{establishmentId}/affiliations")
    public ResponseEntity<List<AffiliationRequestResponse>> listEstablishmentAffiliations(
            @PathVariable UUID establishmentId,
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Affiliation] GET /establishment/{}/affiliations — owner={}",
                establishmentId, user.getUserId());

        List<AffiliationRequestResponse> response = affiliationRepository
                .findByEstablishmentAndStatus(establishmentId, AffiliationStatus.PENDING)
                .stream()
                .map(AffiliationRequestResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/affiliations/{id}/accept")
    public ResponseEntity<AffiliationRequestResponse> acceptAffiliation(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Affiliation] PATCH /affiliations/{}/accept — user={}", id, user.getUserId());

        AffiliationRequestResponse response = resolveAffiliationUseCase.accept(user.getUserId(), id);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/affiliations/{id}/reject")
    public ResponseEntity<AffiliationRequestResponse> rejectAffiliation(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Affiliation] PATCH /affiliations/{}/reject — user={}", id, user.getUserId());

        AffiliationRequestResponse response = resolveAffiliationUseCase.reject(user.getUserId(), id);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/affiliations/{id}")
    public ResponseEntity<AffiliationRequestResponse> cancelAffiliation(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Affiliation] DELETE /affiliations/{} — user={}", id, user.getUserId());

        AffiliationRequestResponse response = resolveAffiliationUseCase.cancel(user.getUserId(), id);
        return ResponseEntity.ok(response);
    }
}
