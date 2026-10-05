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
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ospaulos.vupth.domain.shared.BusinessClock;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@AllArgsConstructor
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

    @Override
    public CutRequestResponse createCutRequest(CutRequestRequest request, UUID clientId) {
        log.info("[CutRequest] create — client={} professional={} establishment={}",
                clientId, request.professionalId(), request.establishmentId());

        Establishment establishment = establishmentRepository.findById(request.establishmentId())
                .orElseThrow(() -> new EstablishmentNotFoundException(request.establishmentId()));
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

        OffsetDateTime now = OffsetDateTime.now();
        CutRequest cut = new CutRequest();
        cut.setClient(client);
        cut.setProfessional(professional);
        cut.setEstablishment(establishment);
        cut.setServiceItems(serviceItems);
        cut.setStatus(CutRequestStatus.PENDING);
        cut.setCreatedAt(now);
        cut.setExpiresAt(expiresAtFor(request.desiredTime(), now));
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

        OffsetDateTime now = OffsetDateTime.now();
        if (cut.getExpiresAt() != null && now.isAfter(cut.getExpiresAt())) {
            // Defensivo: mesmo antes do timer da fatia 3 rodar, um aceite fora da janela auto-nega.
            cut.setStatus(CutRequestStatus.EXPIRED);
            cut.setRespondedAt(now);
            cutRequestRepository.save(cut);
            throw new CutRequestExpiredException(cutRequestId);
        }

        // Aceite explicito = autorizacao: nasce um Attendance SCHEDULED na cauda (bypassa o gate C1).
        Attendance attendance = attendanceService.createScheduledFromCutRequest(
                cut.getProfessional(), cut.getEstablishment(), cut.getClient(), cut.getServiceItems());

        cut.setStatus(CutRequestStatus.ACCEPTED);
        cut.setRespondedAt(now);
        cut.setResultingAttendance(attendance);
        CutRequest saved = cutRequestRepository.save(cut);

        queuePublisher.broadcastQueue(cut.getEstablishment().getId());
        queuePublisher.notifyClient(cut.getClient().getId(), QueueNotification.of(
                "CUT_ACCEPTED", attendance.getId(), cut.getEstablishment().getId(),
                "Seu pedido de corte foi aceito — voce entrou na fila.").withCutRequestId(cut.getId()));
        pushPublisher.notifyUser(cut.getClient().getId(), "Pedido de corte aceito",
                "Voce entrou na fila.",
                Map.of("type", "CUT_ACCEPTED",
                        "attendanceId", String.valueOf(attendance.getId()),
                        "establishmentId", String.valueOf(cut.getEstablishment().getId())));

        log.info("[CutRequest] accept — id={} ACCEPTED -> attendance={}", cutRequestId, attendance.getId());
        return CutRequestResponse.fromEntity(saved);
    }

    @Override
    public CutRequestResponse refuseCutRequest(UUID cutRequestId, UUID professionalId) {
        log.info("[CutRequest] refuse — id={} professional={}", cutRequestId, professionalId);
        CutRequest cut = loadOwnedPending(cutRequestId, professionalId);

        cut.setStatus(CutRequestStatus.REFUSED);
        cut.setRespondedAt(OffsetDateTime.now());
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
        OffsetDateTime now = OffsetDateTime.now();
        List<CutRequest> due = cutRequestRepository.findByStatusAndExpiresAtBefore(CutRequestStatus.PENDING, now);
        if (due.isEmpty()) {
            return 0;
        }
        for (CutRequest cut : due) {
            cut.setStatus(CutRequestStatus.EXPIRED);
            cut.setRespondedAt(now);
            cutRequestRepository.save(cut);

            queuePublisher.notifyClient(cut.getClient().getId(), QueueNotification.of(
                    "CUT_EXPIRED", null, cut.getEstablishment().getId(),
                    "Seu pedido de corte expirou sem resposta do profissional.").withCutRequestId(cut.getId()));
            pushPublisher.notifyUser(cut.getClient().getId(), "Pedido de corte expirado",
                    "Ninguem respondeu a tempo. Tente novamente quando a loja abrir.",
                    Map.of("type", "CUT_EXPIRED",
                            "cutRequestId", String.valueOf(cut.getId()),
                            "establishmentId", String.valueOf(cut.getEstablishment().getId())));
            log.info("[CutRequest] expire — id={} PENDING -> EXPIRED (expiresAt={})", cut.getId(), cut.getExpiresAt());
        }
        return due.size();
    }

    /**
     * DEC-27: o pedido para HOJE (ou sem dia) vale {@value #VALIDITY_MINUTES} min; para um dia seguinte,
     * cuja agenda ainda nao abriu, fica ativo ate o profissional decidir ({@code null} = sem vencimento).
     */
    static OffsetDateTime expiresAtFor(OffsetDateTime desiredTime, OffsetDateTime now) {
        if (desiredTime != null && BusinessClock.dateAt(desiredTime).isAfter(BusinessClock.dateAt(now))) {
            return null;
        }
        return now.plusMinutes(VALIDITY_MINUTES);
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

    /** Avisa o profissional (WS pessoal + push) que ha um pedido de corte aguardando decisao. */
    private void notifyProfessional(CutRequest cut) {
        UUID profId = cut.getProfessional().getId(); // == user id (MapsId)
        queuePublisher.notifyClient(profId, QueueNotification.of(
                "CUT_REQUESTED", null, cut.getEstablishment().getId(),
                "Um cliente solicitou um corte — aceite ou recuse.").withCutRequestId(cut.getId()));
        pushPublisher.notifyUser(profId, "Novo pedido de corte",
                "Um cliente quer ser atendido. Aceite ou recuse antes de expirar.",
                Map.of("type", "CUT_REQUESTED",
                        "cutRequestId", String.valueOf(cut.getId()),
                        "establishmentId", String.valueOf(cut.getEstablishment().getId())));
    }
}
