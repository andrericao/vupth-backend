package com.ospaulos.vupth.web.rest.attendance.controller;

import com.ospaulos.vupth.api.attendance.dto.request.CreateIntentionRequest;
import com.ospaulos.vupth.api.attendance.dto.request.UpdateIntentionRequest;
import com.ospaulos.vupth.api.attendance.dto.response.IntentionAgendaResponse;
import com.ospaulos.vupth.api.attendance.dto.response.IntentionResponse;
import com.ospaulos.vupth.domain.attendance.service.AttendanceService;
import com.ospaulos.vupth.domain.attendance.service.IntentionPromotionScheduler;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

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
    private final IntentionPromotionScheduler promotionScheduler;

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

        // Pos-commit: a cabeca/timer de promocao se recalculam (a nova intencao pode ser a proxima a promover).
        promotionScheduler.reschedule();
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /attendances/intentions/professional/me — visao de agenda do profissional (fatia 2): as
     * suas intencoes futuras (fora da fila viva), congeladas no estimado da criacao, + o quadro da
     * barra de encaixe (projectedQueueEnd + expediente). O front agrupa por dia. Requer PROFESSIONAL.
     */
    @GetMapping("/professional/me")
    public ResponseEntity<IntentionAgendaResponse> professionalAgenda(
            @AuthenticationPrincipal UserDetailsImpl principal) {
        log.info("INTENTION CONTROLLER AGENDA (professional) — professional={}", principal.getUserId());
        return ResponseEntity.ok(attendanceService.getProfessionalAgenda(principal.getUserId()));
    }

    /**
     * GET /attendances/intentions/client/me — visao de agenda do cliente (fatia 2): os proprios
     * agendamentos (INTENTION) em qualquer loja, + um quadro por profissional distinto. Requer CLIENT.
     */
    @GetMapping("/client/me")
    public ResponseEntity<IntentionAgendaResponse> clientAgenda(
            @AuthenticationPrincipal UserDetailsImpl principal) {
        log.info("INTENTION CONTROLLER AGENDA (client) — client={}", principal.getUserId());
        return ResponseEntity.ok(attendanceService.getClientAgenda(principal.getUserId()));
    }

    /**
     * PATCH /attendances/intentions/{id} — o cliente dono (JWT) altera o horario pretendido. Passa
     * pelo mesmo algoritmo do create. 200 com a intencao + estimativa recalculada. Erros: 422 fora do
     * expediente / horizonte / anterior ao fim da fila, 409 agenda cheia, 400 no passado ou status
     * invalido (ja promovido/cancelado), 403 nao-dono, 404 inexistente. Requer ROLE_CLIENT.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<IntentionResponse> updateIntention(
            @PathVariable("id") UUID id,
            @RequestBody @Valid UpdateIntentionRequest request,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("INTENTION CONTROLLER UPDATE — client={} intention={} desiredTime={}",
                principal.getUserId(), id, request.desiredTime());

        IntentionResponse response = attendanceService.updateIntention(
                id, request.desiredTime(), principal.getUserId());

        // Pos-commit: horario mudou -> a cabeca e o gatilho se corrigem (inclusive "mudou pra mais cedo").
        promotionScheduler.reschedule();
        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /attendances/intentions/{id} — o cliente dono (JWT) cancela a intencao (INTENTION ->
     * CANCELLED). 204 sem corpo. Erros: 400 status invalido (ja promovido/cancelado), 403 nao-dono,
     * 404 inexistente. Requer ROLE_CLIENT.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelIntention(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("INTENTION CONTROLLER CANCEL — client={} intention={}", principal.getUserId(), id);

        attendanceService.cancelIntention(id, principal.getUserId());

        // Pos-commit: se a cabeca era esta, o timer avanca pra proxima (ou fica ocioso).
        promotionScheduler.reschedule();
        return ResponseEntity.noContent().build();
    }
}
