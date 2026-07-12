package com.ospaulos.vupth.web.rest.attendance.controller;

import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceListResponse;
import com.ospaulos.vupth.api.queue.dto.response.QueueResponse;
import com.ospaulos.vupth.api.websocket.dto.QueueNotification;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.service.AttendanceService;
import com.ospaulos.vupth.domain.professional.service.ProfessionalService;
import com.ospaulos.vupth.domain.queue.swap.service.QueueSwapService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import com.ospaulos.vupth.infrastructure.websocket.QueueWebSocketPublisher;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/attendances")
@AllArgsConstructor
public class AttendanceController {

    private final AttendanceService attendanceService;
    private final ProfessionalService professionalService;
    private final QueueWebSocketPublisher queuePublisher;
    private final QueueSwapService queueSwapService;
    private final com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher pushPublisher;

    @GetMapping
    public ResponseEntity<List<AttendanceListResponse>> listAttendances(
            @RequestParam(required = false) UUID establishmentId,
            @RequestParam(required = false) UUID professionalId,
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) AttendanceStatus status,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("ATTENDANCE CONTROLLER LIST — user={} establishment={} professional={} client={} dateFrom={} dateTo={} status={}",
                principal.getUserId(), establishmentId, professionalId, clientId, dateFrom, dateTo, status);

        List<AttendanceListResponse> response = attendanceService.listAttendances(
                principal.getUserId(), establishmentId, professionalId, clientId, dateFrom, dateTo, status);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/client/me")
    public ResponseEntity<List<AttendanceListResponse>> listClientAttendances(
            @RequestParam(required = false) AttendanceStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("ATTENDANCE CONTROLLER LIST CLIENT — clientId={} status={} dateFrom={} dateTo={}",
                principal.getUserId(), status, dateFrom, dateTo);

        List<AttendanceListResponse> response = attendanceService.listClientAttendances(
                principal.getUserId(), status, dateFrom, dateTo);
        return ResponseEntity.ok(response);
    }

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
                principal.getUserId(), request.professionalId(), request.establishmentId());

        // clientId comes from the token, not the body — body value is ignored
        AttendanceRequest secureRequest = new AttendanceRequest(
                request.professionalId(),
                request.establishmentId(),
                principal.getUserId(),   // ← sempre do token
                request.serviceItemIds(),
                request.status(),
                request.scheduledTime(),
                request.estimatedMinutes(),
                request.local(),
                request.targetTime()    // item #40: horario-alvo (intencao de fila)
        );

        var response = attendanceService.createAttendance(secureRequest);

        // WS: cliente entrou na fila → atualiza a fila ao vivo da loja
        queuePublisher.broadcastQueue(response.establishmentId());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
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

        // #42: saiu da fila → propostas de troca pendentes deixam de existir
        queueSwapService.expirePendingForAttendance(attendanceId);
        // WS: saiu da fila → atualiza a fila ao vivo da loja
        queuePublisher.broadcastQueue(response.establishmentId());

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

        // #42: saiu da fila → propostas de troca pendentes deixam de existir
        queueSwapService.expirePendingForAttendance(attendanceId);
        // WS: saiu da fila → atualiza a fila ao vivo da loja
        queuePublisher.broadcastQueue(response.establishmentId());

        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /attendances/{attendanceId}/reschedule?targetTime=2026-07-03T16:30:00Z
     *
     * Item #43 (Opcao A) — cliente com espera longa remaneja a propria vez escolhendo um
     * horario-alvo: o atendimento (SCHEDULED) vira INTENCAO de fila (#40). Só o proprio
     * cliente (JWT). 422 se o alvo for infactivel; 400 se status invalido / alvo no passado.
     * Requer ROLE_CLIENT.
     */
    @PatchMapping("/{attendanceId}/reschedule")
    public ResponseEntity<AttendanceInQueueResponse> reschedule(
            @PathVariable UUID attendanceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) java.time.OffsetDateTime targetTime,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("ATTENDANCE CONTROLLER RESCHEDULE {} by client={} targetTime={}",
                attendanceId, principal.getUserId(), targetTime);

        AttendanceInQueueResponse response = attendanceService.rescheduleWithTargetTime(
                attendanceId, principal.getUserId(), targetTime);

        // #42: a posicao mudou → propostas de troca pendentes deixam de valer
        queueSwapService.expirePendingForAttendance(attendanceId);
        // WS: a fila mudou → atualiza a fila ao vivo da loja
        queuePublisher.broadcastQueue(response.establishmentId());

        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /attendances/{attendanceId}/move-to-end
     *
     * Item #43 (Opcao B) — cliente com espera longa desce a propria vez para o fim da fila
     * (sem sair). Só o proprio cliente (JWT). 400 se status invalido. Requer ROLE_CLIENT.
     */
    @PatchMapping("/{attendanceId}/move-to-end")
    public ResponseEntity<AttendanceInQueueResponse> moveToEnd(
            @PathVariable UUID attendanceId,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("ATTENDANCE CONTROLLER MOVE-TO-END {} by client={}", attendanceId, principal.getUserId());

        AttendanceInQueueResponse response = attendanceService.moveToEndOfQueue(
                attendanceId, principal.getUserId());

        // #42: a posicao mudou → propostas de troca pendentes deixam de valer
        queueSwapService.expirePendingForAttendance(attendanceId);
        // WS: a fila mudou → atualiza a fila ao vivo da loja
        queuePublisher.broadcastQueue(response.establishmentId());

        return ResponseEntity.ok(response);
    }

    /**
     * POST /attendances/{attendanceId}/rating
     *
     * Item #6 — o cliente dono avalia o atendimento concluido (1-5 estrelas + comentario opcional).
     * Só o proprio cliente (JWT), so em FINISHED. 409 no segundo voto; 400 se nao estiver FINISHED;
     * 403 se nao for o dono (inclui convidado). Se o atendimento tem 1 servico, a nota alimenta o
     * profissional E o ServiceItem; com 2+ servicos, so o profissional. Requer ROLE_CLIENT.
     */
    @PostMapping("/{attendanceId}/rating")
    public ResponseEntity<com.ospaulos.vupth.api.attendance.dto.response.AttendanceRatingResponse> rateAttendance(
            @PathVariable UUID attendanceId,
            @RequestBody @Valid com.ospaulos.vupth.api.attendance.dto.request.AttendanceRatingRequest request,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("ATTENDANCE CONTROLLER RATE {} by client={} score={}",
                attendanceId, principal.getUserId(), request.score());

        var response = attendanceService.rateAttendance(attendanceId, principal.getUserId(), request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
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
        QueueResponse response = professionalService.startAttendance(principal.getUserId(), attendanceId);

        // #42: foi atendido → propostas de troca pendentes deixam de existir
        queueSwapService.expirePendingForAttendance(attendanceId);

        // WS: fila ao vivo + avisa o cliente que chegou a vez dele
        AttendanceInQueueResponse att = attendanceService.getById(attendanceId);
        queuePublisher.broadcastQueue(response.establishmentId());
        queuePublisher.notifyClient(att.clientId(), QueueNotification.of(
                "ATTENDANCE_STARTED", attendanceId, att.establishmentId(),
                "É a sua vez! Seu atendimento começou."));
        // Push (mesmo aviso do WS, para o app em background)
        pushPublisher.notifyUser(att.clientId(), "É a sua vez!", "Seu atendimento começou.",
                java.util.Map.of("type", "ATTENDANCE_STARTED",
                        "attendanceId", attendanceId.toString(),
                        "establishmentId", String.valueOf(att.establishmentId())));

        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /attendances/{attendanceId}/late
     *
     * Marca um cliente que nao chegou como LATE (item #19): ele cai uma posicao na fila
     * (atras do proximo) mas NAO sai. Pode ser chamado de novo para cair mais.
     * O profissional logado deve ser o dono do attendance. Requer PROFESSIONAL role.
     */
    @PatchMapping("/{attendanceId}/late")
    public ResponseEntity<?> markLate(
            @PathVariable UUID attendanceId,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("ATTENDANCE CONTROLLER LATE {} by user={}", attendanceId, principal.getUserId());

        QueueResponse response = professionalService.markAttendanceLate(principal.getUserId(), attendanceId);

        // WS: a fila mudou (cliente caiu de posicao) → atualiza a fila ao vivo da loja
        queuePublisher.broadcastQueue(response.establishmentId());

        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /attendances/{attendanceId}/undo-late
     *
     * Desfaz o LATE (item #19): o cliente volta a SCHEDULED na posicao atual (a que caiu).
     * O profissional logado deve ser o dono do attendance. Requer PROFESSIONAL role.
     */
    @PatchMapping("/{attendanceId}/undo-late")
    public ResponseEntity<?> undoLate(
            @PathVariable UUID attendanceId,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("ATTENDANCE CONTROLLER UNDO-LATE {} by user={}", attendanceId, principal.getUserId());

        QueueResponse response = professionalService.undoAttendanceLate(principal.getUserId(), attendanceId);

        // WS: a fila mudou → atualiza a fila ao vivo da loja
        queuePublisher.broadcastQueue(response.establishmentId());

        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /attendances/{attendanceId}/no-show
     *
     * Escalada de no-show (item #39): remove da fila (CANCELLED) um cliente que ja estava
     * LATE (#19) e nao compareceu ao ser chamado de novo, registrando um strike NO_SHOW.
     * Exige que o atendimento esteja em LATE. O profissional logado deve ser o dono.
     * Requer PROFESSIONAL role.
     */
    @PatchMapping("/{attendanceId}/no-show")
    public ResponseEntity<?> markNoShow(
            @PathVariable UUID attendanceId,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("ATTENDANCE CONTROLLER NO-SHOW {} by user={}", attendanceId, principal.getUserId());

        QueueResponse response = professionalService.markAttendanceNoShow(principal.getUserId(), attendanceId);

        // WS: a fila mudou (cliente removido) → atualiza a fila ao vivo da loja
        queuePublisher.broadcastQueue(response.establishmentId());

        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /attendances/{attendanceId}/fit-in?aheadCount=5
     *
     * Encaixa o cliente no meio da fila (item #38): o atendimento e movido para logo apos
     * {@code aheadCount} clientes. Os de tras absorvem o tempo do encaixe de forma rampada
     * (sem solavanco na estimativa). O profissional logado deve ser o dono. Requer PROFESSIONAL role.
     */
    @PatchMapping("/{attendanceId}/fit-in")
    public ResponseEntity<?> fitIn(
            @PathVariable UUID attendanceId,
            @RequestParam(defaultValue = "0") int aheadCount,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("ATTENDANCE CONTROLLER FIT-IN {} aheadCount={} by user={}",
                attendanceId, aheadCount, principal.getUserId());

        QueueResponse response = professionalService.fitInAttendance(principal.getUserId(), attendanceId, aheadCount);

        // WS: a fila mudou (cliente encaixado) → atualiza a fila ao vivo da loja
        queuePublisher.broadcastQueue(response.establishmentId());

        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /attendances/{attendanceId}/undo-fit
     *
     * Desfaz o encaixe (item #38): o atendimento deixa de rampar e passa a contribuir o tempo
     * cheio, mantendo a posicao atual. O profissional logado deve ser o dono. Requer PROFESSIONAL role.
     */
    @PatchMapping("/{attendanceId}/undo-fit")
    public ResponseEntity<?> undoFit(
            @PathVariable UUID attendanceId,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("ATTENDANCE CONTROLLER UNDO-FIT {} by user={}", attendanceId, principal.getUserId());

        QueueResponse response = professionalService.undoFitInAttendance(principal.getUserId(), attendanceId);

        // WS: a fila mudou → atualiza a fila ao vivo da loja
        queuePublisher.broadcastQueue(response.establishmentId());

        return ResponseEntity.ok(response);
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

        AttendanceInQueueResponse att = attendanceService.getById(attendanceId);
        QueueResponse response = professionalService.finishAttendance(principal.getUserId(), attendanceId);

        // WS: fila ao vivo + avisa o cliente que o atendimento foi concluido
        queuePublisher.broadcastQueue(response.establishmentId());
        queuePublisher.notifyClient(att.clientId(), QueueNotification.of(
                "ATTENDANCE_FINISHED", attendanceId, att.establishmentId(),
                "Seu atendimento foi concluído."));
        // Push (mesmo aviso do WS, para o app em background)
        pushPublisher.notifyUser(att.clientId(), "Atendimento concluído", "Seu atendimento foi concluído.",
                java.util.Map.of("type", "ATTENDANCE_FINISHED",
                        "attendanceId", attendanceId.toString(),
                        "establishmentId", String.valueOf(att.establishmentId())));

        return ResponseEntity.ok(response);
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

        QueueResponse response = professionalService.pauseAttendance(attendanceId, principal.getUserId());
        queuePublisher.broadcastQueue(response.establishmentId());

        return ResponseEntity.ok(response);
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

        QueueResponse response = professionalService.resumeAttendance(attendanceId, principal.getUserId());
        queuePublisher.broadcastQueue(response.establishmentId());

        return ResponseEntity.ok(response);
    }
}

