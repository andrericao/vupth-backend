package com.ospaulos.vupth.web.rest.affiliation.controller;

import com.ospaulos.vupth.api.affiliation.dto.request.CreateAffiliationInvitationDTO;
import com.ospaulos.vupth.api.affiliation.dto.request.CreateAffiliationRequestDTO;
import com.ospaulos.vupth.api.affiliation.dto.request.CreateEmailInvitationDTO;
import com.ospaulos.vupth.api.affiliation.dto.response.AffiliationRequestResponse;
import com.ospaulos.vupth.api.affiliation.dto.response.EmailInvitationResponse;
import com.ospaulos.vupth.api.affiliation.dto.response.InviteByEmailResponse;
import com.ospaulos.vupth.api.affiliation.usecase.InviteByEmailUseCase;
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
    private final InviteByEmailUseCase inviteByEmailUseCase;
    private final AffiliationRequestRepository affiliationRepository;
    private final com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository establishmentRepository;

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

    /**
     * Sem {@code status}: so os PENDING, como sempre. {@code ?status=ALL} devolve o historico inteiro
     * (aceitos, recusados e cancelados, com {@code resolvedAt}); {@code ?status=ACCEPTED} etc. filtra
     * (DEC-19 · historico-de-vinculo).
     */
    @GetMapping("/professional/me/affiliations")
    public ResponseEntity<List<AffiliationRequestResponse>> listProfessionalAffiliations(
            @AuthenticationPrincipal UserDetailsImpl user,
            @RequestParam(required = false) String status) {

        log.info("[Affiliation] GET /professional/me/affiliations — user={} status={}", user.getUserId(), status);

        AffiliationStatus filter = parseStatus(status);
        List<AffiliationRequestResponse> response = (filter == null
                        ? affiliationRepository.findByProfessional(user.getUserId())
                        : affiliationRepository.findByProfessionalAndStatus(user.getUserId(), filter))
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

    /**
     * Convite pelo e-mail, para quem ainda nao tem conta (convite-sem-conta, Partner S30). 201 com
     * {@code outcome}: INVITATION_SENT (o e-mail ja e de um profissional — virou o convite comum) ou
     * WAITING_ACCOUNT (guardado; vira convite comum quando a pessoa criar o perfil profissional).
     */
    @PostMapping("/establishment/{establishmentId}/email-invitations")
    public ResponseEntity<InviteByEmailResponse> inviteByEmail(
            @PathVariable UUID establishmentId,
            @AuthenticationPrincipal UserDetailsImpl user,
            @RequestBody @Valid CreateEmailInvitationDTO request) {

        log.info("[Affiliation] POST /establishment/{}/email-invitations — owner={}", establishmentId, user.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(inviteByEmailUseCase.invite(user.getUserId(), establishmentId, request.email()));
    }

    /** Os convites por e-mail que ainda esperam a conta (so o dono da loja). */
    @GetMapping("/establishment/{establishmentId}/email-invitations")
    public ResponseEntity<List<EmailInvitationResponse>> listEmailInvitations(
            @PathVariable UUID establishmentId,
            @AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(inviteByEmailUseCase.listPending(user.getUserId(), establishmentId));
    }

    /** O dono cancela um convite por e-mail que ainda espera a conta. */
    @DeleteMapping("/email-invitations/{id}")
    public ResponseEntity<EmailInvitationResponse> cancelEmailInvitation(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(inviteByEmailUseCase.cancel(user.getUserId(), id));
    }

    /**
     * Sem {@code status}: so os PENDING, como sempre. {@code ?status=ALL} e o historico do vinculo
     * que o dono ve — aceitos e recusados com a data ({@code resolvedAt}) — e {@code ?status=REJECTED}
     * etc. filtra (DEC-19 · historico-de-vinculo). So o dono da loja lista.
     */
    @GetMapping("/establishment/{establishmentId}/affiliations")
    public ResponseEntity<List<AffiliationRequestResponse>> listEstablishmentAffiliations(
            @PathVariable UUID establishmentId,
            @AuthenticationPrincipal UserDetailsImpl user,
            @RequestParam(required = false) String status) {

        log.info("[Affiliation] GET /establishment/{}/affiliations — owner={} status={}",
                establishmentId, user.getUserId(), status);

        com.ospaulos.vupth.domain.establishment.model.Establishment establishment = establishmentRepository.findById(establishmentId)
                .orElseThrow(() -> new com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException(establishmentId));
        if (establishment.getOwner() == null || !establishment.getOwner().getId().equals(user.getUserId())) {
            throw new com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException("You can only list the affiliations of your own establishments.");
        }

        AffiliationStatus filter = parseStatus(status);
        List<AffiliationRequestResponse> response = (filter == null
                        ? affiliationRepository.findByEstablishment(establishmentId)
                        : affiliationRepository.findByEstablishmentAndStatus(establishmentId, filter))
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

    /** null = todos (ALL); sem parametro = PENDING (o contrato de sempre); valor invalido = 400. */
    private static AffiliationStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return AffiliationStatus.PENDING;
        }
        if ("ALL".equalsIgnoreCase(status)) {
            return null;
        }
        try {
            return AffiliationStatus.valueOf(status.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // 400 INVALID_ARGUMENT (GlobalExceptionHandler)
            throw new IllegalArgumentException("status must be ALL, PENDING, ACCEPTED, REJECTED or CANCELLED");
        }
    }
}
