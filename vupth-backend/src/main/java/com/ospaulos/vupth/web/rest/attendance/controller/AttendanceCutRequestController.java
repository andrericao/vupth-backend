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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

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

    /**
     * PATCH /attendances/cut-requests/{id}/accept — o profissional dono ACEITA (fatia 2): nasce um
     * Attendance SCHEDULED na cauda da fila e o cliente e notificado. Requer PROFESSIONAL role.
     * Erros: 409 nao-PENDING (`CUT_REQUEST_NOT_PENDING`) / expirado (`CUT_REQUEST_EXPIRED`),
     * 403 nao-dono, 404 inexistente.
     */
    @PatchMapping("/{id}/accept")
    public ResponseEntity<CutRequestResponse> accept(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetailsImpl principal) {
        log.info("CUT-REQUEST CONTROLLER ACCEPT — id={} professional={}", id, principal.getUserId());
        return ResponseEntity.ok(cutRequestService.acceptCutRequest(id, principal.getUserId()));
    }

    /**
     * PATCH /attendances/cut-requests/{id}/refuse — o profissional dono RECUSA (fatia 2): o pedido
     * vira REFUSED e o cliente e notificado. Requer PROFESSIONAL role. Mesmos erros do accept
     * (exceto expirado).
     */
    @PatchMapping("/{id}/refuse")
    public ResponseEntity<CutRequestResponse> refuse(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetailsImpl principal) {
        log.info("CUT-REQUEST CONTROLLER REFUSE — id={} professional={}", id, principal.getUserId());
        return ResponseEntity.ok(cutRequestService.refuseCutRequest(id, principal.getUserId()));
    }
}
