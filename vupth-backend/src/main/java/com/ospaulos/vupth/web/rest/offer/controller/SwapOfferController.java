package com.ospaulos.vupth.web.rest.offer.controller;

import com.ospaulos.vupth.api.queue.offer.dto.request.SwapOfferCreateRequest;
import com.ospaulos.vupth.api.queue.offer.dto.response.SwapOfferResponse;
import com.ospaulos.vupth.domain.queue.offer.service.SwapOfferService;
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
 * Oferta ABERTA de troca de posicao na fila (Client C13 / Partner S11). O cliente publica uma oferta
 * (adiantar/atrasar) e qualquer cliente elegivel na mesma fila pode aceitar. O partner e passivo.
 *
 * <p>Todos os endpoints exigem ROLE_CLIENT (ver SecurityConfig). A propriedade e sempre validada
 * contra o token, nunca confiada no body.</p>
 */
@Slf4j
@RestController
@RequestMapping("/swap-offers")
@AllArgsConstructor
public class SwapOfferController {

    private final SwapOfferService swapOfferService;

    /** POST /swap-offers — publica uma oferta (na fila: {@code {attendanceId, direction}}). */
    @PostMapping
    public ResponseEntity<SwapOfferResponse> create(
            @RequestBody @Valid SwapOfferCreateRequest request,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("SWAP-OFFER CONTROLLER CREATE — by client={}", principal.getUserId());
        SwapOfferResponse response = swapOfferService.create(request, principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** GET /swap-offers?professionalId= — ofertas que EU posso aceitar (filtradas por posicao/direcao). */
    @GetMapping
    public ResponseEntity<List<SwapOfferResponse>> listAcceptable(
            @RequestParam UUID professionalId,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("SWAP-OFFER CONTROLLER LIST ACCEPTABLE — professional={} client={}",
                professionalId, principal.getUserId());
        return ResponseEntity.ok(swapOfferService.listAcceptable(principal.getUserId(), professionalId));
    }

    /** GET /swap-offers/me — minhas ofertas OPEN (para cancelar). */
    @GetMapping("/me")
    public ResponseEntity<List<SwapOfferResponse>> listMine(
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("SWAP-OFFER CONTROLLER LIST MINE — client={}", principal.getUserId());
        return ResponseEntity.ok(swapOfferService.listMine(principal.getUserId()));
    }

    /** PATCH /swap-offers/{id}/accept — aceito a oferta e executo a troca. */
    @PatchMapping("/{offerId}/accept")
    public ResponseEntity<SwapOfferResponse> accept(
            @PathVariable UUID offerId,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("SWAP-OFFER CONTROLLER ACCEPT — offer={} by client={}", offerId, principal.getUserId());
        return ResponseEntity.ok(swapOfferService.accept(offerId, principal.getUserId()));
    }

    /** DELETE /swap-offers/{id} — o solicitante cancela a propria oferta. */
    @DeleteMapping("/{offerId}")
    public ResponseEntity<Void> cancel(
            @PathVariable UUID offerId,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("SWAP-OFFER CONTROLLER CANCEL — offer={} by client={}", offerId, principal.getUserId());
        swapOfferService.cancel(offerId, principal.getUserId());
        return ResponseEntity.noContent().build();
    }
}
