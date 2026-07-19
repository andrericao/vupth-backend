package com.ospaulos.vupth.web.rest.attendance.controller;

import com.ospaulos.vupth.api.attendance.dto.request.CutRequestRequest;
import com.ospaulos.vupth.api.attendance.dto.response.CutRequestResponse;
import com.ospaulos.vupth.domain.attendance.cut.service.CutRequestService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pedido de corte (BUG5 C2 — "solicitar corte"). Quando a loja esta fechada p/ novos (gate C1), o
 * cliente registrado nao entra sozinho; registra aqui um pedido sob aprovacao que o profissional
 * aceita/recusa (fatia 2) e que expira sozinho em 5min sem resposta (fatia 3).
 */
@RestController
@RequestMapping("/attendances/cut-requests")
@AllArgsConstructor
@Slf4j
public class AttendanceCutRequestController {

    private final CutRequestService cutRequestService;

    /**
     * POST /attendances/cut-requests — o cliente autenticado (clientId do JWT) solicita um corte.
     * 201 com o pedido (PENDING + expiresAt). Erros: 409 loja aberta agora (`CUT_REQUEST_NOT_ALLOWED`),
     * 422 servico de outro profissional, 404 profissional/servico/loja inexistente. Requer ROLE_CLIENT.
     */
    @PostMapping
    public ResponseEntity<CutRequestResponse> createCutRequest(
            @RequestBody @Valid CutRequestRequest request,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("CUT-REQUEST CONTROLLER CREATE — client={} professional={} establishment={}",
                principal.getUserId(), request.professionalId(), request.establishmentId());

        CutRequestResponse response = cutRequestService.createCutRequest(request, principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
