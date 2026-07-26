package com.ospaulos.vupth.domain.queue.offer.service.impl;

import com.ospaulos.vupth.api.queue.offer.dto.request.SwapOfferCreateRequest;
import com.ospaulos.vupth.api.queue.offer.dto.response.SwapOfferResponse;
import com.ospaulos.vupth.api.websocket.dto.QueueNotification;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceNotFoundException;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Oferta ABERTA de troca de posicao (Client C13). Fatia 1 = solicitante NA fila (posicao explicita);
 * a oferta de FORA da fila e rejeitada com 422 ate a fatia 2. Ver {@link SwapOfferService}.
 */
@Slf4j
@Service
@AllArgsConstructor
@Transactional
public class SwapOfferServiceImpl implements SwapOfferService {

    private final SwapOfferRepository swapOfferRepository;
    private final AttendanceRepository attendanceRepository;
    private final ProfessionalRepository professionalRepository;
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
            // Fatia 2 — solicitante fora da fila entra na vaga de quem aceitar (quem aceita vai pro fim).
            throw new SwapOfferNotAllowedException(
                    "Oferta de troca a partir de FORA da fila sera habilitada na proxima fatia.");
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

    @Override
    @Transactional(readOnly = true)
    public List<SwapOfferResponse> listAcceptable(UUID callerClientId, UUID professionalId) {
        Professional professional = professionalRepository
                .findByIdWithBaseUserAndEstablishment(professionalId).orElse(null);
        if (professional == null || professional.getEstablishment() == null) {
            return List.of();
        }
        UUID establishmentId = professional.getEstablishment().getId();

        List<Attendance> waiting = attendanceRepository
                .findWaitingQueueByProfessionalAndEstablishment(professionalId, establishmentId);

        Map<UUID, Integer> posByAttendance = new HashMap<>();
        Integer callerPos = null;
        for (int i = 0; i < waiting.size(); i++) {
            Attendance a = waiting.get(i);
            posByAttendance.put(a.getId(), i);
            if (callerPos == null && a.getClient() != null && a.getClient().getId().equals(callerClientId)) {
                callerPos = i;
            }
        }
        if (callerPos == null) {
            return List.of(); // quem nao esta na fila nao tem posicao -> nada a aceitar
        }

        final int myPos = callerPos;
        return swapOfferRepository.findByProfessionalAndStatus(professionalId, SwapOfferStatus.OPEN).stream()
                .filter(o -> o.getRequesterClient() == null
                        || !o.getRequesterClient().getId().equals(callerClientId)) // nao ofertas minhas
                .filter(o -> isEligible(o, myPos, posByAttendance))
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

        List<Attendance> waiting = attendanceRepository
                .findWaitingQueueByProfessionalAndEstablishment(professionalId, establishmentId);

        Map<UUID, Integer> posByAttendance = new HashMap<>();
        Attendance callerAttendance = null;
        for (int i = 0; i < waiting.size(); i++) {
            Attendance a = waiting.get(i);
            posByAttendance.put(a.getId(), i);
            if (callerAttendance == null && a.getClient() != null
                    && a.getClient().getId().equals(callerClientId)) {
                callerAttendance = a;
            }
        }
        if (callerAttendance == null) {
            throw new SwapOfferNotAllowedException("Voce precisa estar na fila deste profissional para aceitar a troca.");
        }

        int callerPos = posByAttendance.get(callerAttendance.getId());
        if (!isEligible(offer, callerPos, posByAttendance)) {
            throw new SwapOfferNotAllowedException(
                    "Voce nao e elegivel para aceitar esta oferta (posicao/direcao incompativel).");
        }

        // Fatia 1: solicitante NA fila -> troca 1-por-1 dos scheduledTime (mesmo padrao do #42).
        Attendance requesterAttendance = offer.getRequesterAttendance();
        if (requesterAttendance == null) {
            throw new SwapOfferNotAllowedException("Oferta de fora da fila sera habilitada na proxima fatia.");
        }
        if (requesterAttendance.getStatus() != AttendanceStatus.SCHEDULED) {
            throw new SwapOfferNotAllowedException("O solicitante nao esta mais aguardando na fila.");
        }

        OffsetDateTime callerSlot = callerAttendance.getScheduledTime();
        callerAttendance.setScheduledTime(requesterAttendance.getScheduledTime());
        requesterAttendance.setScheduledTime(callerSlot);
        attendanceRepository.save(callerAttendance);
        attendanceRepository.save(requesterAttendance);

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
     * na fila (todos estao a frente do "fim").
     */
    private boolean isEligible(SwapOffer offer, int callerPos, Map<UUID, Integer> posByAttendance) {
        if (!offer.isFromQueue()) {
            return offer.getDirection() == SwapOfferDirection.EARLIER;
        }
        Integer requesterPos = posByAttendance.get(offer.getRequesterAttendance().getId());
        if (requesterPos == null) {
            return false; // solicitante ja saiu da fila -> oferta obsoleta
        }
        return switch (offer.getDirection()) {
            case EARLIER -> requesterPos > callerPos;
            case LATER -> requesterPos < callerPos;
        };
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
