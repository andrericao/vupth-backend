package com.ospaulos.vupth.web.rest.attendance.controller;

import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;
import com.ospaulos.vupth.domain.attendance.service.AttendanceService;
import com.ospaulos.vupth.domain.professional.service.ProfessionalService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/attendances")
@AllArgsConstructor
public class AttendanceController {

    private final AttendanceService attendanceService;
    private final ProfessionalService professionalService;

    /**
     * GET /attendances/{attendanceId}
     *
     * Busca um attendance pelo ID. Retorna 404 se não encontrado.
     * Usado pelo Postman e pelo frontend para verificar o estado de um atendimento.
     * Requer: autenticação (qualquer role).
     */
    @GetMapping("/{attendanceId}")
    public ResponseEntity<AttendanceInQueueResponse> getById(
            @PathVariable UUID attendanceId) {

        log.info("ATTENDANCE CONTROLLER GET BY ID — attendanceId={}", attendanceId);
        return ResponseEntity.ok(attendanceService.getById(attendanceId));
    }

    /**
     * POST /attendances
     *
     * Cliente autenticado entra na fila de um profissional.
     * Requer: Bearer token com ROLE_CLIENT.
     *
     * Security: clientId is extracted from the JWT token (@AuthenticationPrincipal),
     * not from the body — prevents a client from booking on behalf of another.
     */
    @PostMapping
    public ResponseEntity<AttendanceInQueueResponse> create(
            @RequestBody @Valid AttendanceRequest request,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("ATTENDANCE CONTROLLER CREATE — client={} prof={} estab={}",
                principal.getUserId(), request.professionalID(), request.establishmentId());

        // clientId comes from the token, not the body — body value is ignored
        AttendanceRequest secureRequest = new AttendanceRequest(
                request.professionalID(),
                request.establishmentId(),
                principal.getUserId(),   // ← sempre do token
                request.serviceItemIds(),
                request.status(),
                request.scheduledTime(),
                request.estimatedMinutes(),
                request.local()
        );

        var response = attendanceService.createAttendance(secureRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(AttendanceInQueueResponse.fromEntity(response));
    }

    /**
     * PUT /attendances/{attendanceId}/cancel
     *
     * Client cancels their own attendance (status → CANCELED).
     * Requer: Bearer token com ROLE_CLIENT.
     *
     * Security: clientId comes from JWT token — CancelAttendanceRequest body is no longer needed,
     * mantido por compatibilidade mas ignorado no service (usa o do token).
     */
    @PutMapping("/{attendanceId}/cancel")
    public ResponseEntity<AttendanceInQueueResponse> cancelAttendance(
            @PathVariable UUID attendanceId,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("ATTENDANCE CONTROLLER CANCEL {} by client={}", attendanceId, principal.getUserId());

        AttendanceInQueueResponse response = attendanceService.cancelAttendance(attendanceId, principal.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /attendances/{attendanceId}/leave-queue
     *
     * Cliente sai da fila voluntariamente (status → CANCELED).
     * Somente o próprio cliente (por JWT) pode chamar este endpoint.
     * Requer: Bearer token com ROLE_CLIENT.
     */
    @DeleteMapping("/{attendanceId}/leave-queue")
    public ResponseEntity<AttendanceInQueueResponse> leaveQueue(
            @PathVariable UUID attendanceId,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("ATTENDANCE CONTROLLER LEAVE QUEUE {} by client={}", attendanceId, principal.getUserId());

        AttendanceInQueueResponse response = attendanceService.leaveQueue(attendanceId, principal.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /attendances/{attendanceId}/start
     *
     * Inicia um attendance SCHEDULED → IN_PROGRESS.
     * O profissional logado deve ser o dono do attendance.
     * Requer: Bearer token (PROFESSIONAL role).
     */
    @PatchMapping("/{attendanceId}/start")
    public ResponseEntity<?> startAttendance(
            @PathVariable UUID attendanceId,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("ATTENDANCE CONTROLLER START {} by user={}", attendanceId, principal.getUserId());

        // Pass principal.getUserId() directly — ProfessionalServiceImpl validates ownership.
        // Avoids triggering a lazy proxy load on attendance.getProfessional() outside a TX.
        return ResponseEntity.ok(
                professionalService.startAttendance(principal.getUserId(), attendanceId)
        );
    }

    /**
     * PATCH /attendances/{attendanceId}/finish
     *
     * Finaliza um attendance IN_PROGRESS → FINISHED.
     * O profissional logado deve ser o dono do attendance.
     * Requer: Bearer token (PROFESSIONAL role).
     */
    @PatchMapping("/{attendanceId}/finish")
    public ResponseEntity<?> finishAttendance(
            @PathVariable UUID attendanceId,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("ATTENDANCE CONTROLLER FINISH {} by user={}", attendanceId, principal.getUserId());

        return ResponseEntity.ok(
                professionalService.finishAttendance(principal.getUserId(), attendanceId)
        );
    }

    /**
     * PATCH /attendances/{attendanceId}/pause
     *
     * Pausa um attendance IN_PROGRESS → PAUSED.
     * O profissional logado deve ser o dono do attendance.
     * Requer: Bearer token (PROFESSIONAL role).
     */
    @PatchMapping("/{attendanceId}/pause")
    public ResponseEntity<?> pauseAttendance(
            @PathVariable UUID attendanceId,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("ATTENDANCE CONTROLLER PAUSE {} by user={}", attendanceId, principal.getUserId());

        return ResponseEntity.ok(
                professionalService.pauseAttendance(attendanceId, principal.getUserId())
        );
    }

    /**
     * PATCH /attendances/{attendanceId}/resume
     *
     * Retoma um attendance PAUSED → IN_PROGRESS.
     * O profissional logado deve ser o dono do attendance.
     * Requer: Bearer token (PROFESSIONAL role).
     */
    @PatchMapping("/{attendanceId}/resume")
    public ResponseEntity<?> resumeAttendance(
            @PathVariable UUID attendanceId,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("ATTENDANCE CONTROLLER RESUME {} by user={}", attendanceId, principal.getUserId());

        return ResponseEntity.ok(
                professionalService.resumeAttendance(attendanceId, principal.getUserId())
        );
    }
}

