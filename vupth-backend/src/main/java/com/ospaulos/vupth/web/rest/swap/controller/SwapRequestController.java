package com.ospaulos.vupth.web.rest.swap.controller;

import com.ospaulos.vupth.api.queue.swap.dto.request.SwapRequestCreateRequest;
import com.ospaulos.vupth.api.queue.swap.dto.response.SwapRequestResponse;
import com.ospaulos.vupth.domain.queue.swap.service.QueueSwapService;
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
 * Troca de posicao na fila por consentimento mutuo (item #42). O cliente A propoe a troca do seu
 * atendimento com o de outro cliente (B) da mesma fila; a troca so efetiva quando B aceita. Broadcast
 * WS da fila e notificacoes pessoais sao feitos dentro do {@link QueueSwapService}.
 *
 * <p>Todos os endpoints exigem ROLE_CLIENT (ver SecurityConfig). A propriedade do atendimento e
 * sempre validada contra o token, nunca confiada no body.</p>
 */
@Slf4j
@RestController
@RequestMapping("/swap-requests")
@AllArgsConstructor
public class SwapRequestController {

    private final QueueSwapService queueSwapService;

    /** POST /swap-requests — cliente A propoe a troca (proposer dele) com o atendimento alvo (B). */
    @PostMapping
    public ResponseEntity<SwapRequestResponse> propose(
            @RequestBody @Valid SwapRequestCreateRequest request,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("SWAP CONTROLLER PROPOSE — proposer={} target={} by client={}",
                request.proposerAttendanceId(), request.targetAttendanceId(), principal.getUserId());

        SwapRequestResponse response = queueSwapService.propose(
                request.proposerAttendanceId(), request.targetAttendanceId(), principal.getUserId());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** PATCH /swap-requests/{id}?accept=true|false — cliente B responde a proposta. */
    @PatchMapping("/{swapRequestId}")
    public ResponseEntity<SwapRequestResponse> respond(
            @PathVariable UUID swapRequestId,
            @RequestParam boolean accept,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("SWAP CONTROLLER RESPOND — swap={} accept={} by client={}",
                swapRequestId, accept, principal.getUserId());

        SwapRequestResponse response = queueSwapService.respond(swapRequestId, principal.getUserId(), accept);
        return ResponseEntity.ok(response);
    }

    /** GET /swap-requests/me — propostas PENDING que envolvem o cliente autenticado (recebidas/enviadas). */
    @GetMapping("/me")
    public ResponseEntity<List<SwapRequestResponse>> myPending(
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("SWAP CONTROLLER LIST MINE — client={}", principal.getUserId());
        return ResponseEntity.ok(queueSwapService.listPendingForClient(principal.getUserId()));
    }
}
