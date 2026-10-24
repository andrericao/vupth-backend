package com.ospaulos.vupth.domain.attendance.cut.service.impl;

import com.ospaulos.vupth.api.attendance.dto.request.CutRequestRequest;
import com.ospaulos.vupth.api.attendance.dto.response.CutRequestResponse;
import com.ospaulos.vupth.api.websocket.dto.QueueNotification;
import com.ospaulos.vupth.domain.attendance.cut.CutRequestStatus;
import com.ospaulos.vupth.domain.attendance.cut.model.CutRequest;
import com.ospaulos.vupth.domain.attendance.cut.repository.CutRequestRepository;
import com.ospaulos.vupth.domain.attendance.cut.service.CutRequestService;
import com.ospaulos.vupth.domain.attendance.exception.CutRequestExpiredException;
import com.ospaulos.vupth.domain.attendance.exception.CutRequestNotPendingException;
import com.ospaulos.vupth.domain.attendance.exception.ServiceItemNotOfProfessionalException;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.service.AttendanceService;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.client.exception.ClientNotFoundException;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.client.repository.ClientRepository;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotAffiliatedException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.serviceItem.exception.ServiceItemNotFoundException;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import com.ospaulos.vupth.infrastructure.websocket.QueueWebSocketPublisher;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ospaulos.vupth.domain.shared.BusinessClock;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CutRequestServiceImpl implements CutRequestService {

    /** Validade do pedido: expira em 5min sem resposta (auto-nega — fatia 3). */
    static final long VALIDITY_MINUTES = 5;

    private final CutRequestRepository cutRequestRepository;
    private final ProfessionalRepository professionalRepository;
    private final EstablishmentRepository establishmentRepository;
    private final ClientRepository clientRepository;
    private final ServiceItemRepository serviceItemRepository;
    private final AttendanceService attendanceService;
    private final QueueWebSocketPublisher queuePublisher;
    private final PushNotificationPublisher pushPublisher;

    /** Relogio do servico. Os testes de borda do vencimento (pedido-vence-no-horario) trocam por um fixo. */
    private Clock clock = Clock.systemDefaultZone();

    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Override
    public CutRequestResponse createCutRequest(CutRequestRequest request, UUID clientId) {
        log.info("[CutRequest] create — client={} professional={} establishment={}",
                clientId, request.professionalId(), request.establishmentId());

        Establishment establishment = establishmentRepository.findById(request.establishmentId())
                .orElseThrow(() -> new EstablishmentNotFoundException(request.establishmentId()));
        // desativar-conta: loja desativada ou removida nao recebe pedido de corte.
        if (!establishment.acceptsClients()) {
            throw new com.ospaulos.vupth.domain.establishment.exception.EstablishmentUnavailableException(establishment.getId());
        }
        Professional professional = professionalRepository.findById(request.professionalId())
                .orElseThrow(() -> new ProfessionalNotFoundException(request.professionalId()));
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ClientNotFoundException(clientId));

        // Profissional tem de ser afiliado a loja do pedido.
        if (professional.getEstablishment() == null
                || !professional.getEstablishment().getId().equals(establishment.getId())) {
            throw new ProfessionalNotAffiliatedException(professional.getId());
        }

        // D9 (25/09): pedido de corte vira entrada na fila — nao existe para quem trabalha so com agenda.
        if (professional.getOperationMode() == com.ospaulos.vupth.domain.professional.valueObject.OperationMode.AGENDA_ONLY) {
            throw new com.ospaulos.vupth.domain.professional.exception.ProfessionalAgendaOnlyException(professional.getId());
        }
        // DEC-27 (Andre, 05/10): o cliente SEMPRE pode pedir — loja aberta ou fechada, profissional em Fora,
        // fila fechada — e quem decide e o profissional. Saiu o 409 CUT_REQUEST_NOT_ALLOWED da loja aberta.

        List<ServiceItem> serviceItems = serviceItemRepository.findAllById(request.serviceItemIds());
        if (serviceItems.isEmpty()) {
            throw new ServiceItemNotFoundException();
        }
        // Invariante servico<->profissional (mesma do createAttendance — BUG8): todo item e do prof.
        for (ServiceItem item : serviceItems) {
            if (item.getProfessional() == null || !professional.getId().equals(item.getProfessional().getId())) {
                throw new ServiceItemNotOfProfessionalException(item.getId(), professional.getId());
            }
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        CutRequest cut = new CutRequest();
        cut.setClient(client);
        cut.setProfessional(professional);
        cut.setEstablishment(establishment);
        cut.setServiceItems(serviceItems);
        cut.setStatus(CutRequestStatus.PENDING);
        cut.setCreatedAt(now);
        cut.setExpiresAt(expiresAtFor(request.desiredTime(), now, professional.getWorkStatus()));
        cut.setDesiredTime(request.desiredTime());
        CutRequest saved = cutRequestRepository.save(cut);

        notifyProfessional(saved);

        log.info("[CutRequest] create — id={} PENDING expiresAt={}", saved.getId(), saved.getExpiresAt());
        return CutRequestResponse.fromEntity(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CutRequestResponse> listPendingForProfessional(UUID professionalId) {
        List<CutRequest> pending = cutRequestRepository
                .findByProfessionalIdAndStatusOrderByCreatedAtAsc(professionalId, CutRequestStatus.PENDING);
        log.info("[CutRequest] list pending — professional={} count={}", professionalId, pending.size());
        return pending.stream().map(CutRequestResponse::fromEntity).toList();
    }

    @Override
    // noRollbackFor: o aceite defensivo persiste o EXPIRED (auto-nega) mesmo lancando 409 —
    // sem isso o rollback padrao da RuntimeException desfaz o flip e o pedido volta a PENDING.
    @Transactional(noRollbackFor = CutRequestExpiredException.class)
    public CutRequestResponse acceptCutRequest(UUID cutRequestId, UUID professionalId) {
        log.info("[CutRequest] accept — id={} professional={}", cutRequestId, professionalId);
        CutRequest cut = loadOwnedPending(cutRequestId, professionalId);

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (isExpired(cut, now)) {
            // Defensivo: mesmo antes do timer rodar, um aceite fora da janela auto-nega — e avisa como
            // o timer avisaria (pedido-vence-no-horario: aceitar as 14:30 um pedido das 14:00 nao vale).
            cut.setStatus(CutRequestStatus.EXPIRED);
            cut.setRespondedAt(now);
            cutRequestRepository.save(cut);
            notifyExpired(cut);
            throw new CutRequestExpiredException(cutRequestId);
        }

        // Pedido para outro dia (aceite-vira-agendamento): vira agendamento (INTENTION) naquele dia e
        // horario. Para hoje ou sem dia: aceite explicito = autorizacao, nasce um SCHEDULED na cauda
        // (bypassa o gate C1).
        boolean otherDay = isForAnotherDay(cut.getDesiredTime(), now);
        Attendance attendance = otherDay
                ? attendanceService.createIntentionFromCutRequest(cut.getProfessional(), cut.getEstablishment(),
                        cut.getClient(), cut.getServiceItems(), cut.getDesiredTime())
                : attendanceService.createScheduledFromCutRequest(
                        cut.getProfessional(), cut.getEstablishment(), cut.getClient(), cut.getServiceItems());

        cut.setStatus(CutRequestStatus.ACCEPTED);
        cut.setRespondedAt(now);
        cut.setResultingAttendance(attendance);
        CutRequest saved = cutRequestRepository.save(cut);

        String accepted = otherDay
                ? "Seu pedido de corte foi aceito — voce esta agendado para " + formatDay(cut.getDesiredTime()) + "."
                : "Seu pedido de corte foi aceito — voce entrou na fila.";
        if (!otherDay) {
            queuePublisher.broadcastQueue(cut.getEstablishment().getId());
        }
        queuePublisher.notifyClient(cut.getClient().getId(), QueueNotification.of(
                "CUT_ACCEPTED", attendance.getId(), cut.getEstablishment().getId(), accepted)
                .withCutRequestId(cut.getId()));
        pushPublisher.notifyUser(cut.getClient().getId(), "Pedido de corte aceito",
                otherDay ? "Voce esta agendado para " + formatDay(cut.getDesiredTime()) + "."
                        : "Voce entrou na fila.",
                Map.of("type", "CUT_ACCEPTED",
                        "attendanceId", String.valueOf(attendance.getId()),
                        "attendanceStatus", String.valueOf(attendance.getStatus()),
                        "establishmentId", String.valueOf(cut.getEstablishment().getId())));

        log.info("[CutRequest] accept — id={} ACCEPTED -> attendance={}", cutRequestId, attendance.getId());
        return CutRequestResponse.fromEntity(saved);
    }

    @Override
    public CutRequestResponse refuseCutRequest(UUID cutRequestId, UUID professionalId) {
        log.info("[CutRequest] refuse — id={} professional={}", cutRequestId, professionalId);
        CutRequest cut = loadOwnedPending(cutRequestId, professionalId);

        cut.setStatus(CutRequestStatus.REFUSED);
        cut.setRespondedAt(OffsetDateTime.now(clock));
        CutRequest saved = cutRequestRepository.save(cut);

        queuePublisher.notifyClient(cut.getClient().getId(), QueueNotification.of(
                "CUT_REFUSED", null, cut.getEstablishment().getId(),
                "Seu pedido de corte foi recusado.").withCutRequestId(cut.getId()));
        pushPublisher.notifyUser(cut.getClient().getId(), "Pedido de corte recusado",
                "O profissional nao pode te atender agora.",
                Map.of("type", "CUT_REFUSED",
                        "cutRequestId", String.valueOf(cutRequestId),
                        "establishmentId", String.valueOf(cut.getEstablishment().getId())));

        log.info("[CutRequest] refuse — id={} REFUSED", cutRequestId);
        return CutRequestResponse.fromEntity(saved);
    }

    @Override
    public int expireDueCutRequests() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<CutRequest> due = cutRequestRepository.findByStatusAndExpiresAtBefore(CutRequestStatus.PENDING, now);
        if (due.isEmpty()) {
            return 0;
        }
        for (CutRequest cut : due) {
            cut.setStatus(CutRequestStatus.EXPIRED);
            cut.setRespondedAt(now);
            cutRequestRepository.save(cut);
            notifyExpired(cut);
            log.info("[CutRequest] expire — id={} PENDING -> EXPIRED (expiresAt={})", cut.getId(), cut.getExpiresAt());
        }
        return due.size();
    }

    /**
     * Quando o pedido vence (DEC-27 com a emenda pedido-vence-no-horario, Andre 10/10: "o pedido dura ate
     * o horario de interesse ou ate o profissional aceitar"). Nunca fica sem vencimento:
     * <ul>
     *   <li>com {@code desiredTime} (hoje ou outro dia): vence no horario pedido;</li>
     *   <li>sem horario, profissional em Fora ("assim que abrir"): vence as 23:59 de hoje, no fuso da loja;</li>
     *   <li>sem horario, profissional em expediente: {@value #VALIDITY_MINUTES} min.</li>
     * </ul>
     */
    static OffsetDateTime expiresAtFor(OffsetDateTime desiredTime, OffsetDateTime now,
                                       ProfessionalStatus professionalStatus) {
        if (desiredTime != null) {
            return desiredTime;
        }
        OffsetDateTime fiveMinutes = now.plusMinutes(VALIDITY_MINUTES);
        if (professionalStatus == ProfessionalStatus.OFF) {
            // Pedido feito nos ultimos minutos do dia nao nasce vencido: vale pelo menos os 5 min.
            OffsetDateTime endOfDay = BusinessClock.at(BusinessClock.dateAt(now), END_OF_DAY);
            return endOfDay.isAfter(fiveMinutes) ? endOfDay : fiveMinutes;
        }
        return fiveMinutes;
    }

    /** O "assim que abrir" com o profissional em Fora vale ate aqui, no dia do pedido. */
    static final java.time.LocalTime END_OF_DAY = java.time.LocalTime.of(23, 59);

    /** Venceu quando a hora chega: as 14:00:00 um pedido para as 14:00 ja nao vale. */
    static boolean isExpired(CutRequest cut, OffsetDateTime now) {
        return cut.getExpiresAt() != null && !now.isBefore(cut.getExpiresAt());
    }

    /** Vencimento (pelo timer ou pelo aceite atrasado): avisa o cliente (WS + push) e tira o card do profissional (WS). */
    private void notifyExpired(CutRequest cut) {
        String message = cut.getDesiredTime() != null
                ? "O horario pedido passou sem resposta do profissional."
                : "Seu pedido de corte expirou sem resposta do profissional.";
        queuePublisher.notifyClient(cut.getClient().getId(), QueueNotification.of(
                "CUT_EXPIRED", null, cut.getEstablishment().getId(), message).withCutRequestId(cut.getId()));
        pushPublisher.notifyUser(cut.getClient().getId(), "Pedido de corte expirado",
                cut.getDesiredTime() != null ? message : "Ninguem respondeu a tempo. Tente novamente quando a loja abrir.",
                Map.of("type", "CUT_EXPIRED",
                        "cutRequestId", String.valueOf(cut.getId()),
                        "establishmentId", String.valueOf(cut.getEstablishment().getId())));
        if (cut.getProfessional() != null) {
            queuePublisher.notifyClient(cut.getProfessional().getId(), QueueNotification.of(
                    "CUT_EXPIRED", null, cut.getEstablishment().getId(),
                    "Um pedido de corte venceu.").withCutRequestId(cut.getId()));
        }
    }

    private static final java.time.format.DateTimeFormatter DAY_AND_TIME =
            java.time.format.DateTimeFormatter.ofPattern("dd/MM 'as' HH:mm");

    /** "10/10 as 14:30", no fuso da loja — o texto do aviso do agendamento. */
    static String formatDay(OffsetDateTime at) {
        return at.atZoneSameInstant(BusinessClock.zone()).format(DAY_AND_TIME);
    }

    /** O pedido e para um dia depois de hoje (no fuso da loja)? Sem dia = hoje. */
    static boolean isForAnotherDay(OffsetDateTime desiredTime, OffsetDateTime now) {
        return desiredTime != null && BusinessClock.dateAt(desiredTime).isAfter(BusinessClock.dateAt(now));
    }

    /** Carrega o pedido (eager nos serviceItems), valida dono e estado PENDING. */
    private CutRequest loadOwnedPending(UUID cutRequestId, UUID professionalId) {
        CutRequest cut = cutRequestRepository.findByIdEager(cutRequestId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "cut request not found: " + cutRequestId));
        if (cut.getProfessional() == null || !cut.getProfessional().getId().equals(professionalId)) {
            throw new EstablishmentAccessDeniedException("You can only decide cut requests directed to you.");
        }
        if (cut.getStatus() != CutRequestStatus.PENDING) {
            throw new CutRequestNotPendingException(cutRequestId, String.valueOf(cut.getStatus()));
        }
        return cut;
    }

    /** Texto do push do pedido de corte: "[nome] pediu um corte" (push-com-acoes); sem nome, "Um cliente". */
    static String requestPushBody(String clientName) {
        String who = clientName == null || clientName.isBlank() ? "Um cliente" : clientName.trim();
        return who + " pediu um corte. Aceite ou recuse antes de expirar.";
    }

    /** Avisa o profissional (WS pessoal + push) que ha um pedido de corte aguardando decisao. */
    private void notifyProfessional(CutRequest cut) {
        UUID profId = cut.getProfessional().getId(); // == user id (MapsId)
        queuePublisher.notifyClient(profId, QueueNotification.of(
                "CUT_REQUESTED", null, cut.getEstablishment().getId(),
                "Um cliente solicitou um corte — aceite ou recuse.").withCutRequestId(cut.getId()));
        String clientName = cut.getClient().getFirstName();
        boolean named = clientName != null && !clientName.isBlank();
        pushPublisher.notifyUser(profId, "Novo pedido de corte", requestPushBody(clientName),
                Map.of("type", "CUT_REQUESTED",
                        "cutRequestId", String.valueOf(cut.getId()),
                        "establishmentId", String.valueOf(cut.getEstablishment().getId()),
                        "clientName", named ? clientName.trim() : ""));
    }
}
