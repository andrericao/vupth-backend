package com.ospaulos.vupth.web.rest.attendance.controller;

import com.ospaulos.vupth.api.attendance.dto.request.AddServiceItemsRequest;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;
import com.ospaulos.vupth.api.attendance.dto.response.ClientAttendanceResponse;
import com.ospaulos.vupth.api.attendance.dto.response.StoreAttendanceResponse;
import com.ospaulos.vupth.api.queue.dto.response.QueueResponse;
import com.ospaulos.vupth.api.websocket.dto.QueueNotification;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.service.AttendanceService;
import com.ospaulos.vupth.domain.professional.service.ProfessionalService;
import com.ospaulos.vupth.domain.queue.offer.service.SwapOfferService;
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
    private final SwapOfferService swapOfferService;
    private final com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher pushPublisher;
    private final com.ospaulos.vupth.domain.attendance.service.EarlierTurnService earlierTurnService;
    private final com.ospaulos.vupth.domain.attendance.service.SuddenDropService suddenDropService;
    private final com.ospaulos.vupth.domain.payment.service.PaymentService paymentService;
    private final com.ospaulos.vupth.domain.client.service.ClientPenaltyService clientPenaltyService;
    private final com.ospaulos.vupth.domain.payment.service.PaymentNotifier paymentNotifier;
    private final com.ospaulos.vupth.domain.attendance.service.NoShowHintService noShowHintService;

    /**
     * GET /attendances — sem {@code page}/{@code size}, a lista inteira de sempre; com um dos dois
     * (historico-rapido, 04/10), o envelope {items, page, size, totalItems, totalPages, hasNext},
     * cortado no banco. size de 1 a 100 (padrao 20); fora disso, 422.
     */
    @GetMapping
    public ResponseEntity<?> listAttendances(
            @RequestParam(required = false) UUID establishmentId,
            @RequestParam(required = false) UUID professionalId,
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) AttendanceStatus status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        var pagination = com.ospaulos.vupth.domain.establishment.valueObject.ShowcasePage.from(page, size);

        // Partner BUG8 (sintoma b): o cliente lista os PROPRIOS atendimentos por aqui (?clientId=), com a resposta do
        // CLIENTE (DEC-94: um DTO por perfil). clientId so pode ser o do proprio JWT — nao se lista fila de outro.
        if (clientId != null) {
            if (!clientId.equals(principal.getUserId())) {
                throw new com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException(
                        "You can only list your own attendances.");
            }
            return pagination != null
                    ? ResponseEntity.ok(attendanceService.listClientAttendancesPage(
                            clientId, status, dateFrom, dateTo, pagination))
                    : ResponseEntity.ok(attendanceService.listClientAttendances(clientId, status, dateFrom, dateTo));
        }

        if (pagination != null) {
            return ResponseEntity.ok(attendanceService.listAttendancesPage(
                    principal.getUserId(), establishmentId, professionalId, dateFrom, dateTo,
                    status, pagination));
        }

        log.info("ATTENDANCE CONTROLLER LIST — user={} establishment={} professional={} dateFrom={} dateTo={} status={}",
                principal.getUserId(), establishmentId, professionalId, dateFrom, dateTo, status);

        List<StoreAttendanceResponse> response = attendanceService.listAttendances(
                principal.getUserId(), establishmentId, professionalId, dateFrom, dateTo, status);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/client/me")
    public ResponseEntity<List<ClientAttendanceResponse>> listClientAttendances(
            @RequestParam(required = false) AttendanceStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("ATTENDANCE CONTROLLER LIST CLIENT — clientId={} status={} dateFrom={} dateTo={}",
                principal.getUserId(), status, dateFrom, dateTo);

        List<ClientAttendanceResponse> response = attendanceService.listClientAttendances(
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

        // pagamento-pix: com atendimento finalizado sem pagar, nada de fila nova (409 PAYMENT_PENDING)
        paymentService.assertNoPendingPayment(principal.getUserId());
        // limite-de-duas-multas (DEC-83): com 2 multas devidas, so marca depois de quitar (409 PENALTY_LIMIT_REACHED)
        clientPenaltyService.assertWithinLimit(principal.getUserId());

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
                request.targetTime(),   // item #40: horario-alvo (intencao de fila)
                request.couponCampaignId() // cupom-ativado-em-configuracoes
        );

        var response = attendanceService.createAttendance(secureRequest);

        // WS: cliente entrou na fila → atualiza a fila ao vivo da loja
        queuePublisher.broadcastQueue(response.establishmentId());
        // DEC-101: avisa o profissional so se o unico cliente da fila esta atrasado ha mais de 10 min
        noShowHintService.onQueueEntry(response.id());

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
            // D4: o "Sim" do aviso da multa — o valor que o cliente viu (exitPenaltyValue do wait-time).
            @RequestParam(required = false) java.math.BigDecimal acknowledgedPenalty,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("ATTENDANCE CONTROLLER CANCEL {} by client={}", attendanceId, principal.getUserId());

        AttendanceInQueueResponse response = attendanceService.cancelAttendance(
                attendanceId, principal.getUserId(), acknowledgedPenalty);

        // #42: saiu da fila → propostas de troca pendentes deixam de existir
        queueSwapService.expirePendingForAttendance(attendanceId);
        swapOfferService.expireOpenOffersForAttendance(attendanceId);
        // WS: saiu da fila → atualiza a fila ao vivo da loja
        queuePublisher.broadcastQueue(response.establishmentId());
        // D8 (17/09): quem foi adiantado pela desistencia recebe o convite do horario novo
        offerEarlierTurns(response);

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
            // D4: o "Sim" do aviso da multa — o valor que o cliente viu (exitPenaltyValue do wait-time).
            @RequestParam(required = false) java.math.BigDecimal acknowledgedPenalty,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("ATTENDANCE CONTROLLER LEAVE QUEUE {} by client={}", attendanceId, principal.getUserId());

        AttendanceInQueueResponse response = attendanceService.leaveQueue(
                attendanceId, principal.getUserId(), acknowledgedPenalty);

        // #42: saiu da fila → propostas de troca pendentes deixam de existir
        queueSwapService.expirePendingForAttendance(attendanceId);
        swapOfferService.expireOpenOffersForAttendance(attendanceId);
        // WS: saiu da fila → atualiza a fila ao vivo da loja
        queuePublisher.broadcastQueue(response.establishmentId());
        // D8 (17/09): quem foi adiantado pela desistencia recebe o convite do horario novo
        offerEarlierTurns(response);

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
        swapOfferService.expireOpenOffersForAttendance(attendanceId);
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
        swapOfferService.expireOpenOffersForAttendance(attendanceId);
        // WS: a fila mudou → atualiza a fila ao vivo da loja
        queuePublisher.broadcastQueue(response.establishmentId());

        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /attendances/{attendanceId}/service-items
     *
     * Adicionar servico a atendimento ja na fila (adicionar-servico-na-fila, Client C8). So o proprio
     * cliente (JWT), so em SCHEDULED; 409 ATTENDANCE_NO_ROOM_FOR_SERVICE se houver alguem depois na fila
     * ou uma intencao antes do novo fim. Requer ROLE_CLIENT.
     */
    @PatchMapping("/{attendanceId}/service-items")
    public ResponseEntity<AttendanceInQueueResponse> addServiceItems(
            @PathVariable UUID attendanceId,
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestBody @Valid AddServiceItemsRequest request) {

        log.info("ATTENDANCE CONTROLLER ADD-SERVICE-ITEMS {} by client={}", attendanceId, principal.getUserId());

        AttendanceInQueueResponse response = attendanceService.addServiceItems(
                attendanceId, principal.getUserId(), request.serviceItemIds());

        // A duracao mudou → a troca casada pela duracao (D14) deixa de valer
        queueSwapService.expirePendingForAttendance(attendanceId);
        swapOfferService.expireOpenOffersForAttendance(attendanceId);
        // WS: a fila mudou → atualiza a fila ao vivo da loja
        queuePublisher.broadcastQueue(response.establishmentId());

        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /attendances/{attendanceId}/promote
     *
     * Partner S7 — o profissional dono da fila promove um atendimento em espera para o 1o lugar
     * (indice 0), empurrando os demais, sem inicia-lo. So o profissional dono (JWT), so em SCHEDULED
     * (400 caso contrario). No-op se ja for o 1o. Requer PROFESSIONAL role.
     */
    @PatchMapping("/{attendanceId}/promote")
    public ResponseEntity<AttendanceInQueueResponse> promoteToFront(
            @PathVariable UUID attendanceId,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("ATTENDANCE CONTROLLER PROMOTE {} by professional={}", attendanceId, principal.getUserId());

        AttendanceInQueueResponse response = attendanceService.promoteToFrontOfQueue(
                attendanceId, principal.getUserId());

        // #42: a posicao mudou → propostas de troca pendentes deixam de valer
        queueSwapService.expirePendingForAttendance(attendanceId);
        swapOfferService.expireOpenOffersForAttendance(attendanceId);
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
        swapOfferService.expireOpenOffersForAttendance(attendanceId);

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
     * PATCH /attendances/{attendanceId}/no-show — o cliente nao compareceu (falta-do-cliente, DEC-83, 17/10). O
     * profissional do atendimento ou o dono da loja tira o primeiro da fila depois da espera (10 min da hora comunicada);
     * ele leva multa de R$ 5,00 (configuravel) e sai com o motivo "removido por atraso". Convidado sai sem multa.
     * 409 NO_SHOW_NOT_FIRST / NO_SHOW_TOO_EARLY (com available_at). O 1o e o 2o atraso seguem so avisando ({@code /late}).
     */
    /**
     * GET /attendances/{attendanceId}/no-show-status — regra-do-atraso-na-fila-vazia (DEC-101): se o NAO CHEGOU ja vale
     * e quanto falta ({@code seconds_left}, relogio do servidor). Profissional do atendimento ou dono da loja.
     */
    @GetMapping("/{attendanceId}/no-show-status")
    public ResponseEntity<com.ospaulos.vupth.api.attendance.dto.response.NoShowStatusResponse> noShowStatus(
            @PathVariable UUID attendanceId,
            @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(professionalService.noShowStatus(principal.getUserId(), attendanceId));
    }

    @PatchMapping("/{attendanceId}/no-show")
    public ResponseEntity<?> markNoShow(
            @PathVariable UUID attendanceId,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("ATTENDANCE CONTROLLER NO-SHOW {} by user={}", attendanceId, principal.getUserId());

        QueueResponse response = professionalService.markNoShow(principal.getUserId(), attendanceId);
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
     * Finaliza um attendance IN_PROGRESS ou PAUSED → FINISHED (D12: da pausa, retomar ou encerrar).
     * O profissional logado deve ser o dono do attendance.
     * Requer: Bearer token (PROFESSIONAL role).
     */
    @PatchMapping("/{attendanceId}/finish")
    public ResponseEntity<?> finishAttendance(
            @PathVariable UUID attendanceId,
            @RequestBody(required = false) com.ospaulos.vupth.api.payment.dto.request.FinishAttendanceRequest finishRequest,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("ATTENDANCE CONTROLLER FINISH {} by user={} paymentReceived={}", attendanceId, principal.getUserId(),
                finishRequest != null && finishRequest.received());

        AttendanceInQueueResponse att = attendanceService.getById(attendanceId);
        QueueResponse response = professionalService.finishAttendance(principal.getUserId(), attendanceId);
        // pagamento-pix (adendo 1): o "Sim" do modal termina e marca pago na mesma chamada
        boolean paidNow = finishRequest != null && finishRequest.received()
                && paymentService.confirmOnFinish(attendanceId, principal.getUserId(),
                finishRequest.paymentMethod(), finishRequest.receivedAmount());
        // finish-com-payment-status (S47): a fila volta com o pagamento de quem acabou de terminar
        response = response.withPaymentStatus(paymentService.statusOf(attendanceId));

        // WS: fila ao vivo + avisa o cliente que o atendimento foi concluido
        queuePublisher.broadcastQueue(response.establishmentId());
        queuePublisher.notifyClient(att.clientId(), QueueNotification.of(
                "ATTENDANCE_FINISHED", attendanceId, att.establishmentId(),
                "Seu atendimento foi concluído."));
        if (paidNow) {
            // Um aviso so ao cliente: "Pagamento efetuado", com o agradecimento (o profissional ve na tela)
            paymentNotifier.confirmed(attendanceId);
            return ResponseEntity.ok(response);
        }
        // Push (mesmo aviso do WS, para o app em background). Com o pagamento pendente (DEC-78), um so push,
        // o PAYMENT_PENDING com o botao Pagar, no lugar do ATTENDANCE_FINISHED.
        java.math.BigDecimal pending = paymentService.pendingAmountOf(attendanceId);
        if (pending != null) {
            pushPublisher.notifyUser(att.clientId(), "Atendimento concluído",
                    com.ospaulos.vupth.domain.payment.service.PaymentReminderScheduler.pendingText(pending),
                    java.util.Map.of("type", "PAYMENT_PENDING",
                            "attendanceId", attendanceId.toString(),
                            "establishmentId", String.valueOf(att.establishmentId()),
                            "amount", pending.toPlainString()));
            return ResponseEntity.ok(response);
        }
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

    /**
     * PATCH /attendances/{attendanceId}/accept-earlier-turn
     *
     * O cliente topa o horario adiantado por uma desistencia (D8, 17/09): ele vira o horario prometido,
     * e o atraso volta a contar a partir dele. Sem aceitar, segue valendo o prometido na entrada.
     */
    @PatchMapping("/{attendanceId}/accept-earlier-turn")
    public ResponseEntity<java.util.Map<String, Object>> acceptEarlierTurn(
            @PathVariable UUID attendanceId,
            @AuthenticationPrincipal UserDetailsImpl principal) {
        java.time.OffsetDateTime promisedTime = earlierTurnService.acceptEarlierTurn(attendanceId, principal.getUserId());
        return ResponseEntity.ok(java.util.Map.of("attendanceId", attendanceId, "promisedTime", promisedTime));
    }

    /** DEC-128 "ok" do aviso de queda brusca: aceita o horario novo e encerra a janela sem multa. */
    @PostMapping("/{attendanceId}/sudden-drop/ack")
    public ResponseEntity<java.util.Map<String, Object>> suddenDropAck(@PathVariable UUID attendanceId,
                                                                       @AuthenticationPrincipal UserDetailsImpl principal) {
        java.time.OffsetDateTime promised = suddenDropService.acknowledge(attendanceId, principal.getUserId());
        return ResponseEntity.ok(java.util.Map.of("attendanceId", attendanceId, "promisedTime", promised));
    }

    /** DEC-128 "preciso de mais tempo": as 4 opcoes (trocar, passar a vez, sair, ir para o final) e ate quando valem sem multa. */
    @PostMapping("/{attendanceId}/sudden-drop/need-time")
    public ResponseEntity<com.ospaulos.vupth.domain.attendance.service.SuddenDropService.Options> suddenDropNeedTime(
            @PathVariable UUID attendanceId, @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(suddenDropService.needMoreTime(attendanceId, principal.getUserId()));
    }

    /** DEC-128 "passar a vez": propoe a troca com o proximo da fila; sem o aceite dele, nada muda. */
    @PostMapping("/{attendanceId}/sudden-drop/pass-turn")
    public ResponseEntity<com.ospaulos.vupth.api.queue.swap.dto.response.SwapRequestResponse> suddenDropPassTurn(
            @PathVariable UUID attendanceId, @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(suddenDropService.passTurn(attendanceId, principal.getUserId()));
    }

    /** Best-effort: o aviso nunca quebra a saida de quem desistiu. */
    private void offerEarlierTurns(AttendanceInQueueResponse leaver) {
        try {
            earlierTurnService.offerEarlierTurns(leaver.professionalId(), leaver.establishmentId());
        } catch (Exception e) {
            log.warn("[EarlierTurn] aviso de horario adiantado falhou apos a saida de {}: {}", leaver.id(), e.getMessage());
        }
    }
}
