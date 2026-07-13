package com.ospaulos.vupth.web.rest.attendance.controller;

import com.ospaulos.vupth.api.attendance.dto.request.CreateIntentionRequest;
import com.ospaulos.vupth.api.attendance.dto.response.IntentionResponse;
import com.ospaulos.vupth.domain.attendance.service.AttendanceService;
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
 * Intencao de atendimento (backlog #4 / status INTENTION). O cliente registrado manifesta, pelo
 * app, a intencao de ser atendido num horario PRETENDIDO futuro — tipicamente com a loja fechada
 * ou o dia cheio. A intencao NAO entra na fila viva na hora: sera promovida a SCHEDULED perto do
 * horario (fatia 3). Editar/cancelar (PATCH/DELETE) entram na fatia 3.
 */
@RestController
@RequestMapping("/attendances/intentions")
@AllArgsConstructor
@Slf4j
public class AttendanceIntentionController {

    private final AttendanceService attendanceService;

    /**
     * POST /attendances/intentions — cria uma intencao para o cliente autenticado (clientId do JWT).
     * 201 com a intencao + estimativa de atendimento. Erros: 422 fora do expediente / horizonte,
     * 409 agenda cheia (CHEIO), 400 horario no passado, 404 profissional/servico inexistente.
     * Requer ROLE_CLIENT.
     */
    @PostMapping
    public ResponseEntity<IntentionResponse> createIntention(
            @RequestBody @Valid CreateIntentionRequest request,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("INTENTION CONTROLLER CREATE — client={} professional={} desiredTime={}",
                principal.getUserId(), request.professionalId(), request.desiredTime());

        IntentionResponse response = attendanceService.createIntention(
                request.professionalId(), request.serviceItemIds(), request.desiredTime(), principal.getUserId());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
