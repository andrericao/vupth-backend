package com.ospaulos.vupth.web.rest.establishment.controller;

import com.ospaulos.vupth.api.establishment.usecase.OwnershipTransferUseCase;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Passar a posse da loja com aceite (desativar-conta). O dono pede e cancela; o profissional convidado
 * aceita ou recusa. Sem prazo: o pedido fica pendente ate um dos tres.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class OwnershipTransferController {

    public record TransferRequest(@NotNull UUID professionalId) {}

    private final OwnershipTransferUseCase useCase;

    /** O dono pede: 201; 422 TRANSFER_TARGET_NOT_IN_TEAM; 409 TRANSFER_ALREADY_PENDING. */
    @PostMapping("/establishment/{id}/ownership-transfer")
    public ResponseEntity<OwnershipTransferUseCase.OwnerView> request(
            @PathVariable UUID id,
            @RequestBody @jakarta.validation.Valid TransferRequest body,
            @AuthenticationPrincipal UserDetailsImpl user) {
        log.info("[OwnershipTransfer] POST — establishment={} owner={} to={}", id, user.getUserId(), body.professionalId());
        return ResponseEntity.status(HttpStatus.CREATED).body(useCase.request(user.getUserId(), id, body.professionalId()));
    }

    /** O pendente da loja, para o dono: 200; 404 OWNERSHIP_TRANSFER_NOT_FOUND sem pendente. */
    @GetMapping("/establishment/{id}/ownership-transfer")
    public ResponseEntity<OwnershipTransferUseCase.OwnerView> pending(
            @PathVariable UUID id, @AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(useCase.pendingForOwner(user.getUserId(), id));
    }

    /** Os convites pendentes que o profissional recebeu. */
    @GetMapping("/professional/me/ownership-transfers")
    public ResponseEntity<List<OwnershipTransferUseCase.InviteView>> invites(@AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(useCase.invitesFor(user.getUserId()));
    }

    @PostMapping("/ownership-transfers/{id}/accept")
    public ResponseEntity<Void> accept(@PathVariable UUID id, @AuthenticationPrincipal UserDetailsImpl user) {
        useCase.accept(id, user.getUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/ownership-transfers/{id}/refuse")
    public ResponseEntity<Void> refuse(@PathVariable UUID id, @AuthenticationPrincipal UserDetailsImpl user) {
        useCase.refuse(id, user.getUserId());
        return ResponseEntity.noContent().build();
    }

    /** O dono cancela o proprio pedido pendente. */
    @DeleteMapping("/ownership-transfers/{id}")
    public ResponseEntity<Void> cancel(@PathVariable UUID id, @AuthenticationPrincipal UserDetailsImpl user) {
        useCase.cancel(id, user.getUserId());
        return ResponseEntity.noContent().build();
    }
}
