package com.ospaulos.vupth.domain.queue.offer.service.impl;

import com.ospaulos.vupth.api.queue.offer.dto.request.SwapOfferCreateRequest;
import com.ospaulos.vupth.api.queue.offer.dto.response.SwapOfferResponse;
import com.ospaulos.vupth.api.websocket.dto.QueueNotification;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceNotFoundException;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.attendance.exception.ServiceItemNotOfProfessionalException;
import com.ospaulos.vupth.domain.attendance.service.AttendanceService;
import com.ospaulos.vupth.domain.client.exception.ClientNotFoundException;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.client.repository.ClientRepository;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.serviceItem.exception.ServiceItemNotFoundException;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import com.ospaulos.vupth.domain.queue.offer.SwapOfferDirection;
import com.ospaulos.vupth.domain.queue.offer.SwapOfferStatus;
import com.ospaulos.vupth.domain.queue.offer.exception.SwapOfferNotAllowedException;
import com.ospaulos.vupth.domain.queue.offer.exception.SwapOfferNotFoundException;
import com.ospaulos.vupth.domain.queue.offer.model.SwapOffer;
import com.ospaulos.vupth.domain.queue.offer.repository.SwapOfferRepository;
import com.ospaulos.vupth.domain.queue.offer.service.SwapOfferService;
import com.ospaulos.vupth.domain.queue.service.QueueService;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import com.ospaulos.vupth.infrastructure.websocket.QueueWebSocketPublisher;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Oferta ABERTA de troca de posicao (Client C13). Cobre os dois lados do modelo:
 * <ul>
 *   <li><b>Fatia 1</b> — solicitante NA fila (posicao explicita): o aceite troca os
 *       {@code scheduledTime} dos dois, 1-por-1 (mesmo padrao do #42).</li>
 *   <li><b>Fatia 2</b> — solicitante FORA da fila (posicao implicita = fim, so {@code EARLIER}):
 *       no aceite quem aceitou vai pro FIM e o solicitante nasce na vaga cedida.</li>
 * </ul>
 * Ver {@link SwapOfferService}.
 */
@Slf4j
@Service
@AllArgsConstructor
@Transactional
public class SwapOfferServiceImpl implements SwapOfferService {

    private final SwapOfferRepository swapOfferRepository;
    private final AttendanceRepository attendanceRepository;
    private final ProfessionalRepository professionalRepository;
    private final ClientRepository clientRepository;
    private final ServiceItemRepository serviceItemRepository;
    private final AttendanceService attendanceService;
    private final QueueService queueService;
    private final QueueWebSocketPublisher publisher;
    private final PushNotificationPublisher pushPublisher;

    @Override
    public SwapOfferResponse create(SwapOfferCreateRequest request, UUID requesterClientId) {
        log.info("[SwapOffer] create — attendance={} professional={} direction={} by client={}",
                request.attendanceId(), request.professionalId(), request.direction(), requesterClientId);

        boolean inQueue = request.attendanceId() != null;
        boolean outOfQueue = request.professionalId() != null;
        if (inQueue == outOfQueue) {
            throw new SwapOfferNotAllowedException(
                    "Informe attendanceId (na fila) OU professionalId (fora da fila), nunca ambos.");
        }

        if (outOfQueue) {
            return createFromOutsideQueue(request, requesterClientId);
        }

        Attendance attendance = attendanceRepository.findByIdEager(request.attendanceId())
                .orElseThrow(() -> new AttendanceNotFoundException(request.attendanceId()));

        if (attendance.getClient() == null || !attendance.getClient().getId().equals(requesterClientId)) {
            throw new EstablishmentAccessDeniedException("You can only publish swap offers for your own attendance.");
        }
        if (attendance.getStatus() != AttendanceStatus.SCHEDULED) {
            throw new SwapOfferNotAllowedException(
                    "So um atendimento aguardando (SCHEDULED) pode ofertar troca. Status atual: "
                            + attendance.getStatus());
        }

        SwapOffer offer = new SwapOffer();
        offer.setRequesterClient(attendance.getClient());
        offer.setProfessional(attendance.getProfessional());
        offer.setEstablishment(attendance.getEstablishment());
        offer.setRequesterAttendance(attendance);
        offer.setDirection(request.direction());
        offer.setStatus(SwapOfferStatus.OPEN);
        offer.setCreatedAt(OffsetDateTime.now());
        offer = swapOfferRepository.save(offer);

        log.info("[SwapOffer] create — oferta {} publicada (OPEN, {})", offer.getId(), request.direction());
        return SwapOfferResponse.from(offer);
    }

    /**
     * Fatia 2 — o solicitante ainda NAO esta na fila: publica {@code {professionalId, serviceItemIds,
     * direction: EARLIER}} e fica com posicao implicita = FIM (por isso a oferta e visivel a todos que
     * estao na fila: todos estao a frente do fim). Os servicos vem agora porque o atendimento so nasce
     * no aceite — e nasce com a duracao ja escolhida, que a fila precisa para recalcular os ETAs.
     */
    private SwapOfferResponse createFromOutsideQueue(SwapOfferCreateRequest request, UUID requesterClientId) {
        // Quem esta fora da fila esta, por definicao, atras de todo mundo: so faz sentido ADIANTAR.
        if (request.direction() != SwapOfferDirection.EARLIER) {
            throw new SwapOfferNotAllowedException(
                    "Fora da fila a posicao implicita e o fim, entao so cabe a direcao EARLIER.");
        }

        Professional professional = professionalRepository
                .findByIdWithBaseUserAndEstablishment(request.professionalId())
                .orElseThrow(() -> new ProfessionalNotFoundException(request.professionalId()));
        Establishment establishment = professional.getEstablishment();
        if (establishment == null) {
            throw new SwapOfferNotAllowedException("Este profissional nao esta vinculado a um estabelecimento.");
        }

        if (request.serviceItemIds() == null || request.serviceItemIds().isEmpty()) {
            throw new ServiceItemNotFoundException();
        }
        List<ServiceItem> serviceItems = serviceItemRepository.findAllById(request.serviceItemIds());
        if (serviceItems.size() != request.serviceItemIds().size()) {
            throw new ServiceItemNotFoundException();
        }
        // Invariante servico<->profissional (BUG8c, mesma do createAttendance): todo item e do prof.
        for (ServiceItem item : serviceItems) {
            if (item.getProfessional() == null
                    || !professional.getId().equals(item.getProfessional().getId())) {
                throw new ServiceItemNotOfProfessionalException(item.getId(), professional.getId());
            }
        }

        Client client = clientRepository.findById(requesterClientId)
                .orElseThrow(() -> new ClientNotFoundException(requesterClientId));

        // Ja estar na fila deste profissional torna a posicao implicita mentirosa — nesse caso a
        // oferta correta e a de DENTRO da fila ({attendanceId, direction}).
        boolean alreadyInQueue = attendanceRepository
                .findWaitingQueueByProfessionalAndEstablishment(professional.getId(), establishment.getId())
                .stream()
                .anyMatch(a -> a.getClient() != null && a.getClient().getId().equals(requesterClientId));
        if (alreadyInQueue) {
            throw new SwapOfferNotAllowedException(
                    "Voce ja esta na fila deste profissional — publique a oferta com o seu attendanceId.");
        }

        SwapOffer offer = new SwapOffer();
        offer.setRequesterClient(client);
        offer.setProfessional(professional);
        offer.setEstablishment(establishment);
        offer.setRequesterAttendance(null); // posicao implicita = fim da fila
        offer.setServiceItems(serviceItems);
        offer.setDirection(SwapOfferDirection.EARLIER);
        offer.setStatus(SwapOfferStatus.OPEN);
        offer.setCreatedAt(OffsetDateTime.now());
        offer = swapOfferRepository.save(offer);

        log.info("[SwapOffer] create — oferta {} publicada de FORA da fila (OPEN, EARLIER, {} servico(s))",
                offer.getId(), serviceItems.size());
        return SwapOfferResponse.from(offer);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SwapOfferResponse> listAcceptable(UUID callerClientId, UUID professionalId) {
        Professional professional = professionalRepository
                .findByIdWithBaseUserAndEstablishment(professionalId).orElse(null);
        if (professional == null || professional.getEstablishment() == null) {
            return List.of();
        }
        UUID establishmentId = professional.getEstablishment().getId();

        QueueSnapshot queue = snapshot(professionalId, establishmentId);
        Attendance callerAttendance = queue.attendanceOf(callerClientId);
        if (callerAttendance == null) {
            return List.of(); // quem nao esta na fila nao tem posicao -> nada a aceitar
        }

        final int myPos = queue.posByAttendance().get(callerAttendance.getId());
        return swapOfferRepository.findByProfessionalAndStatus(professionalId, SwapOfferStatus.OPEN).stream()
                .filter(o -> o.getRequesterClient() == null
                        || !o.getRequesterClient().getId().equals(callerClientId)) // nao ofertas minhas
                .filter(o -> isEligible(o, myPos, queue))
                .map(SwapOfferResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SwapOfferResponse> listMine(UUID requesterClientId) {
        return swapOfferRepository.findByRequesterClientAndStatus(requesterClientId, SwapOfferStatus.OPEN)
                .stream()
                .map(SwapOfferResponse::from)
                .toList();
    }

    @Override
    public SwapOfferResponse accept(UUID offerId, UUID callerClientId) {
        log.info("[SwapOffer] accept — offer={} by client={}", offerId, callerClientId);

        SwapOffer offer = swapOfferRepository.findByIdEager(offerId)
                .orElseThrow(() -> new SwapOfferNotFoundException(offerId));

        if (offer.getStatus() != SwapOfferStatus.OPEN) {
            throw new SwapOfferNotAllowedException("Esta oferta de troca nao esta mais disponivel.");
        }
        if (offer.getRequesterClient() != null && offer.getRequesterClient().getId().equals(callerClientId)) {
            throw new SwapOfferNotAllowedException("Voce nao pode aceitar a sua propria oferta.");
        }

        UUID professionalId = offer.getProfessional().getId();
        UUID establishmentId = offer.getEstablishment().getId();

        QueueSnapshot queue = snapshot(professionalId, establishmentId);
        Attendance callerAttendance = queue.attendanceOf(callerClientId);
        if (callerAttendance == null) {
            throw new SwapOfferNotAllowedException("Voce precisa estar na fila deste profissional para aceitar a troca.");
        }

        int callerPos = queue.posByAttendance().get(callerAttendance.getId());
        if (!isEligible(offer, callerPos, queue)) {
            throw new SwapOfferNotAllowedException(
                    "Voce nao e elegivel para aceitar esta oferta (posicao/direcao incompativel).");
        }

        // O atendimento do solicitante DEPOIS da troca — e o que ele recebe na notificacao. Na fatia 1
        // e o que ja existia; na fatia 2 e o que nasce agora na vaga cedida.
        Attendance requesterAttendance = offer.isFromQueue()
                ? acceptFromQueue(offer, callerAttendance)
                : acceptFromOutsideQueue(offer, callerAttendance, queue);

        offer.setStatus(SwapOfferStatus.ACCEPTED);
        offer.setRespondedAt(OffsetDateTime.now());

        Professional professional = professionalRepository.findByIdWithBaseUser(professionalId).orElse(null);
        if (professional != null) {
            queueService.updateQueueEstimates(professional);
        }
        publisher.broadcastQueue(establishmentId);

        // Notifica os DOIS lados da troca (contrato do handoff Front-Client, secao 7).
        notifySwapParty(offer.getRequesterClient() != null ? offer.getRequesterClient().getId() : null,
                requesterAttendance, establishmentId, "SWAP_OFFER_ACCEPTED",
                "Sua oferta de troca de posicao foi aceita.");
        notifySwapParty(callerClientId, callerAttendance, establishmentId, "SWAP_OFFER_ACCEPTED",
                "Troca de posicao confirmada. Sua posicao na fila foi atualizada.");

        log.info("[SwapOffer] accept — oferta {} ACCEPTED, posicoes trocadas", offerId);
        return SwapOfferResponse.from(offer);
    }

    /**
     * Fatia 1 — os dois ja estao na fila: troca 1-por-1 dos {@code scheduledTime} (padrao do #42).
     * Ninguem mais se move.
     */
    private Attendance acceptFromQueue(SwapOffer offer, Attendance callerAttendance) {
        Attendance requesterAttendance = offer.getRequesterAttendance();
        if (requesterAttendance.getStatus() != AttendanceStatus.SCHEDULED) {
            throw new SwapOfferNotAllowedException("O solicitante nao esta mais aguardando na fila.");
        }

        OffsetDateTime callerSlot = callerAttendance.getScheduledTime();
        callerAttendance.setScheduledTime(requesterAttendance.getScheduledTime());
        requesterAttendance.setScheduledTime(callerSlot);
        attendanceRepository.save(callerAttendance);
        attendanceRepository.save(requesterAttendance);
        return requesterAttendance;
    }

    /**
     * Fatia 2 — o solicitante esta FORA da fila: quem aceitou <b>cede a vaga e vai pro FIM</b>
     * ({@code ultimo + 1min}, mesma aritmetica do {@code move-to-end}) e o solicitante <b>nasce na vaga
     * cedida</b>. Continua 1-por-1: so os dois se movem, os demais mantem a posicao.
     *
     * <p>A ordem importa — quem aceitou sai da vaga ANTES de o atendimento novo ser criado nela, senao
     * os dois dividiriam o mesmo {@code scheduledTime} e o meal-lock (#37) contaria a posicao errada.</p>
     */
    private Attendance acceptFromOutsideQueue(SwapOffer offer, Attendance callerAttendance, QueueSnapshot queue) {
        // Obsoleta: o solicitante entrou na fila por conta propria depois de publicar. A posicao
        // implicita (fim) deixou de valer, entao a oferta nao pode mais ser executada como esta.
        UUID requesterClientId = offer.getRequesterClient().getId();
        if (queue.clientIds().contains(requesterClientId)) {
            throw new SwapOfferNotAllowedException(
                    "O solicitante ja entrou na fila por conta propria — esta oferta nao vale mais.");
        }

        OffsetDateTime freedSlot = callerAttendance.getScheduledTime();

        // Quem aceitou vai pro fim (se ja era o ultimo, desce 1min sobre si mesmo — segue atras).
        Attendance last = queue.waiting().get(queue.waiting().size() - 1);
        callerAttendance.setScheduledTime(last.getScheduledTime().plusMinutes(1));
        callerAttendance.setIntent(false);   // vira espera comum no fim, igual ao move-to-end
        callerAttendance.setTargetTime(null);
        attendanceRepository.save(callerAttendance);

        Attendance created = attendanceService.createScheduledFromSwapOffer(
                offer.getProfessional(), offer.getEstablishment(), offer.getRequesterClient(),
                offer.getServiceItems(), freedSlot);
        offer.setResultingAttendance(created);

        log.info("[SwapOffer] accept — fatia 2: solicitante entrou na vaga {} (attendance={}), "
                        + "quem aceitou ({}) foi pro fim", freedSlot, created.getId(), callerAttendance.getId());
        return created;
    }

    @Override
    public void cancel(UUID offerId, UUID requesterClientId) {
        log.info("[SwapOffer] cancel — offer={} by client={}", offerId, requesterClientId);

        SwapOffer offer = swapOfferRepository.findByIdEager(offerId)
                .orElseThrow(() -> new SwapOfferNotFoundException(offerId));

        if (offer.getRequesterClient() == null || !offer.getRequesterClient().getId().equals(requesterClientId)) {
            throw new EstablishmentAccessDeniedException("You can only cancel your own swap offers.");
        }
        if (offer.getStatus() != SwapOfferStatus.OPEN) {
            throw new SwapOfferNotAllowedException("Esta oferta de troca nao esta mais aberta.");
        }

        offer.setStatus(SwapOfferStatus.CANCELLED);
        offer.setRespondedAt(OffsetDateTime.now());
        log.info("[SwapOffer] cancel — oferta {} CANCELLED", offerId);
    }

    @Override
    public void expireOpenOffersForAttendance(UUID attendanceId) {
        List<SwapOffer> open =
                swapOfferRepository.findByRequesterAttendanceAndStatus(attendanceId, SwapOfferStatus.OPEN);
        if (open.isEmpty()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        for (SwapOffer offer : open) {
            offer.setStatus(SwapOfferStatus.EXPIRED);
            offer.setRespondedAt(now);
        }
        swapOfferRepository.saveAll(open);
        log.info("[SwapOffer] expireOpenOffersForAttendance — atendimento {} saiu da fila, {} oferta(s) EXPIRED",
                attendanceId, open.size());
    }

    // ─────────────────────────────────────────────────────────────
    // Elegibilidade (posicao x direcao)
    // ─────────────────────────────────────────────────────────────

    /**
     * O caller (na posicao {@code callerPos}) pode aceitar a oferta?
     * <ul>
     *   <li>{@code EARLIER} (solicitante quer adiantar): visivel para quem esta A FRENTE dele — ou seja,
     *       posicao do solicitante {@code >} a do caller.</li>
     *   <li>{@code LATER} (solicitante quer atrasar): visivel para quem esta ATRAS dele — posicao do
     *       solicitante {@code <} a do caller.</li>
     * </ul>
     * Oferta de FORA da fila (posicao implicita = fim, sempre EARLIER) e visivel para todos que estao
     * na fila (todos estao a frente do "fim") — a menos que o solicitante tenha entrado na fila por
     * conta propria depois de publicar, caso em que a posicao implicita deixou de valer.
     */
    private boolean isEligible(SwapOffer offer, int callerPos, QueueSnapshot queue) {
        if (!offer.isFromQueue()) {
            return offer.getDirection() == SwapOfferDirection.EARLIER
                    && !queue.clientIds().contains(offer.getRequesterClient().getId());
        }
        Integer requesterPos = queue.posByAttendance().get(offer.getRequesterAttendance().getId());
        if (requesterPos == null) {
            return false; // solicitante ja saiu da fila -> oferta obsoleta
        }
        return switch (offer.getDirection()) {
            case EARLIER -> requesterPos > callerPos;
            case LATER -> requesterPos < callerPos;
        };
    }

    /**
     * Fila viva do profissional (SCHEDULED/LATE por {@code scheduledTime}) lida UMA vez, com os
     * indices e os clientes ja mapeados — a base de toda decisao de posicao/elegibilidade.
     */
    private QueueSnapshot snapshot(UUID professionalId, UUID establishmentId) {
        List<Attendance> waiting = attendanceRepository
                .findWaitingQueueByProfessionalAndEstablishment(professionalId, establishmentId);

        Map<UUID, Integer> posByAttendance = new HashMap<>();
        Set<UUID> clientIds = new HashSet<>();
        for (int i = 0; i < waiting.size(); i++) {
            Attendance a = waiting.get(i);
            posByAttendance.put(a.getId(), i);
            if (a.getClient() != null) {
                clientIds.add(a.getClient().getId());
            }
        }
        return new QueueSnapshot(waiting, posByAttendance, clientIds);
    }

    /** Fila viva materializada: ordem, indice por atendimento e clientes presentes. */
    private record QueueSnapshot(List<Attendance> waiting,
                                 Map<UUID, Integer> posByAttendance,
                                 Set<UUID> clientIds) {

        /** Atendimento em espera do cliente informado, ou {@code null} se ele nao esta na fila. */
        Attendance attendanceOf(UUID clientId) {
            return waiting.stream()
                    .filter(a -> a.getClient() != null && a.getClient().getId().equals(clientId))
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * Avisa um dos lados da troca (solicitante ou quem aceitou) no seu canal pessoal + push.
     * O {@code attendanceId} enviado e o do PROPRIO destinatario, para o app dele recarregar a posicao.
     */
    private void notifySwapParty(UUID clientId, Attendance attendance, UUID establishmentId,
                                 String type, String message) {
        if (clientId == null) {
            return; // convidado (sem conta) so recebe o broadcast da fila
        }
        UUID attendanceId = attendance != null ? attendance.getId() : null;
        publisher.notifyClient(clientId, QueueNotification.of(type, attendanceId, establishmentId, message));
        pushPublisher.notifyUser(clientId, "Troca de posicao", message,
                Map.of("type", type, "attendanceId", attendanceId != null ? attendanceId.toString() : ""));
    }
}
