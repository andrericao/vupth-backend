package com.ospaulos.vupth.domain.attendance.cut.service.impl;

import com.ospaulos.vupth.api.attendance.dto.request.CutRequestRequest;
import com.ospaulos.vupth.api.attendance.dto.response.CutRequestResponse;
import com.ospaulos.vupth.api.websocket.dto.QueueNotification;
import com.ospaulos.vupth.domain.attendance.cut.CutRequestStatus;
import com.ospaulos.vupth.domain.attendance.cut.model.CutRequest;
import com.ospaulos.vupth.domain.attendance.cut.repository.CutRequestRepository;
import com.ospaulos.vupth.domain.attendance.cut.service.CutRequestService;
import com.ospaulos.vupth.domain.attendance.exception.CutRequestNotAllowedException;
import com.ospaulos.vupth.domain.attendance.exception.ServiceItemNotOfProfessionalException;
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

        // Gate C2: so faz sentido pedir corte com a loja FECHADA agora. Aberta -> entra normal (409).
        if (establishment.isOpenNow()) {
            throw new CutRequestNotAllowedException(establishment.getId());
        }

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
        cut.setExpiresAt(now.plusMinutes(VALIDITY_MINUTES));
        CutRequest saved = cutRequestRepository.save(cut);

        notifyProfessional(saved);

        log.info("[CutRequest] create — id={} PENDING expiresAt={}", saved.getId(), saved.getExpiresAt());
        return CutRequestResponse.fromEntity(saved);
    }

    /** Avisa o profissional (WS pessoal + push) que ha um pedido de corte aguardando decisao. */
    private void notifyProfessional(CutRequest cut) {
        UUID profId = cut.getProfessional().getId(); // == user id (MapsId)
        queuePublisher.notifyClient(profId, QueueNotification.of(
                "CUT_REQUESTED", cut.getId(), cut.getEstablishment().getId(),
                "Um cliente solicitou um corte — aceite ou recuse."));
        pushPublisher.notifyUser(profId, "Novo pedido de corte",
                "Um cliente quer ser atendido. Aceite ou recuse antes de expirar.",
                Map.of("type", "CUT_REQUESTED",
                        "cutRequestId", String.valueOf(cut.getId()),
                        "establishmentId", String.valueOf(cut.getEstablishment().getId())));
    }
}
