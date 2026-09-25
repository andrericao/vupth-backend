package com.ospaulos.vupth.web.rest.client.controller;

import com.ospaulos.vupth.api.client.dto.response.ClientResponse;
import com.ospaulos.vupth.api.client.dto.response.PendingPenaltiesResponse;
import com.ospaulos.vupth.api.client.usecase.GetClientUseCase;
import com.ospaulos.vupth.domain.client.service.ClientPenaltyService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/clients")
@RequiredArgsConstructor
public class ClientController {

    private final GetClientUseCase getClientUseCase;
    private final ClientPenaltyService clientPenaltyService;

    @GetMapping("/{id}")
    //@PreAuthorize("hasRole('PROFESSIONAL')")
    public ResponseEntity<ClientResponse> getClient(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetailsImpl user){

        log.info("CLIENT CONTROLLER BEGINS");

        ClientResponse response = getClientUseCase.execute(id);

        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    /**
     * Multas que o cliente logado ainda deve (D4). O app as soma aos servicos marcados antes de entrar
     * na fila e cita cada uma pela origem ("corte do dia tal com o profissional tal").
     */
    @GetMapping("/me/pending-penalties")
    public ResponseEntity<PendingPenaltiesResponse> pendingPenalties(
            @AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(clientPenaltyService.pending(user.getUserId()));
    }
}
