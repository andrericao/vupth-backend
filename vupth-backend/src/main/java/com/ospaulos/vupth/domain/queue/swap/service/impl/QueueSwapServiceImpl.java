package com.ospaulos.vupth.domain.queue.swap.service.impl;

import com.ospaulos.vupth.api.queue.swap.dto.response.SwapRequestResponse;
import com.ospaulos.vupth.api.websocket.dto.QueueNotification;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceNotFoundException;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.queue.service.QueueService;
import com.ospaulos.vupth.domain.queue.swap.SwapRequestStatus;
import com.ospaulos.vupth.domain.queue.swap.SwapDurationRule;
import com.ospaulos.vupth.domain.queue.swap.exception.SwapAlreadyPendingException;
import com.ospaulos.vupth.domain.queue.swap.exception.SwapDurationIncompatibleException;
import com.ospaulos.vupth.domain.queue.swap.exception.SwapNotAllowedException;
import com.ospaulos.vupth.domain.queue.swap.exception.SwapRequestNotFoundException;
import com.ospaulos.vupth.domain.queue.swap.model.QueueSwapRequest;
import com.ospaulos.vupth.domain.queue.swap.repository.QueueSwapRequestRepository;
import com.ospaulos.vupth.domain.queue.swap.service.QueueSwapService;
import com.ospaulos.vupth.infrastructure.websocket.QueueWebSocketPublisher;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@AllArgsConstructor
@Transactional
public class QueueSwapServiceImpl implements QueueSwapService {

    private final QueueSwapRequestRepository swapRepository;
    private final AttendanceRepository attendanceRepository;
    private final ProfessionalRepository professionalRepository;
    private final QueueService queueService;
    private final QueueWebSocketPublisher publisher;
    private final com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher pushPublisher;

    @Override
    public SwapRequestResponse propose(UUID proposerAttendanceId, UUID targetAttendanceId, UUID requesterClientId) {
        log.info("[Swap] propose — proposer={} target={} by client={}",
                proposerAttendanceId, targetAttendanceId, requesterClientId);

        if (proposerAttendanceId.equals(targetAttendanceId)) {
            throw new SwapNotAllowedException("Nao e possivel trocar um atendimento com ele mesmo.");
        }

        Attendance proposer = attendanceRepository.findByIdEager(proposerAttendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(proposerAttendanceId));
        Attendance target = attendanceRepository.findByIdEager(targetAttendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(targetAttendanceId));

        // So o dono do atendimento proponente (por token) pode propor.
        if (proposer.getClient() == null || !proposer.getClient().getId().equals(requesterClientId)) {
            throw new EstablishmentAccessDeniedException("Client can only propose swaps for their own attendance");
        }

        assertSameQueue(proposer, target);
        assertSwappable(proposer);
        assertSwappable(target);
        assertDurationCompatible(proposer, target);

        if (swapRepository.existsPendingBetween(proposerAttendanceId, targetAttendanceId, SwapRequestStatus.PENDING)) {
            throw new SwapAlreadyPendingException();
        }

        QueueSwapRequest swap = new QueueSwapRequest();
        swap.setProposer(proposer);
        swap.setTarget(target);
        swap.setStatus(SwapRequestStatus.PENDING);
        swap.setCreatedAt(OffsetDateTime.now());
        swap = swapRepository.save(swap);

        // Avisa o cliente alvo (B) que ha uma proposta de troca para ele responder.
        if (target.getClient() != null) {
            publisher.notifyClient(target.getClient().getId(), QueueNotification.of(
                    "SWAP_REQUESTED", proposer.getId(),
                    proposer.getEstablishment() != null ? proposer.getEstablishment().getId() : null,
                    "Um cliente pediu para trocar de posicao com voce na fila."));
            pushPublisher.notifyUser(target.getClient().getId(), "Pedido de troca de posicao",
                    "Um cliente pediu para trocar de posicao com voce na fila.",
                    java.util.Map.of("type", "SWAP_REQUESTED", "attendanceId", proposer.getId().toString(),
                            "swapRequestId", String.valueOf(swap.getId())));
        }

        log.info("[Swap] propose — criada proposta {} (PENDING)", swap.getId());
        return SwapRequestResponse.from(swap);
    }

    @Override
    public SwapRequestResponse respond(UUID swapRequestId, UUID requesterClientId, boolean accept) {
        log.info("[Swap] respond — swap={} accept={} by client={}", swapRequestId, accept, requesterClientId);

        QueueSwapRequest swap = swapRepository.findByIdEager(swapRequestId)
                .orElseThrow(() -> new SwapRequestNotFoundException(swapRequestId));

        if (swap.getStatus() != SwapRequestStatus.PENDING) {
            throw new SwapNotAllowedException("Esta proposta de troca nao esta mais pendente.");
        }

        Attendance proposer = swap.getProposer();
        Attendance target = swap.getTarget();

        // So o dono do atendimento alvo (cliente B) pode responder.
        if (target.getClient() == null || !target.getClient().getId().equals(requesterClientId)) {
            throw new EstablishmentAccessDeniedException("Only the target client can respond to this swap request");
        }

        if (!accept) {
            swap.setStatus(SwapRequestStatus.REJECTED);
            swap.setRespondedAt(OffsetDateTime.now());
            notifyProposer(proposer, target, "SWAP_REJECTED", "Sua proposta de troca foi recusada.");
            log.info("[Swap] respond — proposta {} REJECTED", swapRequestId);
            return SwapRequestResponse.from(swap);
        }

        // Aceite: revalida que os dois ainda estao trocaveis (podem ter sido atendidos/cancelados).
        assertSameQueue(proposer, target);
        assertSwappable(proposer);
        assertSwappable(target);
        assertDurationCompatible(proposer, target);

        // Troca os scheduledTime -> troca de posicao na fila (mesmo padrao do #19/#38).
        OffsetDateTime proposerSlot = proposer.getScheduledTime();
        proposer.setScheduledTime(target.getScheduledTime());
        target.setScheduledTime(proposerSlot);
        attendanceRepository.save(proposer);
        attendanceRepository.save(target);

        swap.setStatus(SwapRequestStatus.ACCEPTED);
        swap.setRespondedAt(OffsetDateTime.now());

        // Recalcula estimativas e atualiza a fila ao vivo.
        Professional professional = professionalRepository.findByIdWithBaseUser(proposer.getProfessional().getId())
                .orElse(null);
        if (professional != null) {
            queueService.updateQueueEstimates(professional);
        }
        if (proposer.getEstablishment() != null) {
            publisher.broadcastQueue(proposer.getEstablishment().getId());
        }
        notifyProposer(proposer, target, "SWAP_ACCEPTED", "Sua proposta de troca foi aceita.");

        log.info("[Swap] respond — proposta {} ACCEPTED, posicoes trocadas", swapRequestId);
        return SwapRequestResponse.from(swap);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SwapRequestResponse> listPendingForClient(UUID clientId) {
        return swapRepository.findByStatusInvolvingClient(clientId, SwapRequestStatus.PENDING)
                .stream()
                .map(SwapRequestResponse::from)
                .toList();
    }

    @Override
    public void expirePendingForAttendance(UUID attendanceId) {
        List<QueueSwapRequest> pending =
                swapRepository.findByStatusInvolvingAttendance(attendanceId, SwapRequestStatus.PENDING);
        if (pending.isEmpty()) {
            return;
        }
        for (QueueSwapRequest swap : pending) {
            swap.setStatus(SwapRequestStatus.CANCELLED);
            swap.setRespondedAt(OffsetDateTime.now());
        }
        swapRepository.saveAll(pending);
        log.info("[Swap] expirePendingForAttendance — atendimento {} saiu da fila, {} proposta(s) CANCELLED",
                attendanceId, pending.size());
    }

    // ─────────────────────────────────────────────────────────────
    // Validacoes
    // ─────────────────────────────────────────────────────────────

    /** Os dois atendimentos precisam estar na MESMA fila (mesmo profissional + estabelecimento). */
    private void assertSameQueue(Attendance a, Attendance b) {
        UUID profA = a.getProfessional() != null ? a.getProfessional().getId() : null;
        UUID profB = b.getProfessional() != null ? b.getProfessional().getId() : null;
        UUID estA = a.getEstablishment() != null ? a.getEstablishment().getId() : null;
        UUID estB = b.getEstablishment() != null ? b.getEstablishment().getId() : null;
        if (profA == null || !profA.equals(profB) || estA == null || !estA.equals(estB)) {
            throw new SwapNotAllowedException("So e possivel trocar de posicao com alguem da mesma fila.");
        }
    }

    /** D14: ate 10 min de diferenca entre as duracoes estimadas dos dois atendimentos. */
    private void assertDurationCompatible(Attendance a, Attendance b) {
        int minutesA = a.getEstimatedTotalMinutes();
        int minutesB = b.getEstimatedTotalMinutes();
        if (!SwapDurationRule.compatible(minutesA, minutesB)) {
            throw new SwapDurationIncompatibleException(minutesA, minutesB);
        }
    }

    /** O atendimento precisa estar SCHEDULED (esperando) para participar de uma troca. */
    private void assertSwappable(Attendance a) {
        if (a.getStatus() != AttendanceStatus.SCHEDULED) {
            throw new SwapNotAllowedException(
                    "So atendimentos aguardando (SCHEDULED) podem trocar de posicao. Status atual: " + a.getStatus());
        }
    }

    private void notifyProposer(Attendance proposer, Attendance target, String type, String message) {
        if (proposer.getClient() != null) {
            publisher.notifyClient(proposer.getClient().getId(), QueueNotification.of(
                    type, target.getId(),
                    proposer.getEstablishment() != null ? proposer.getEstablishment().getId() : null,
                    message));
            pushPublisher.notifyUser(proposer.getClient().getId(), "Troca de posicao", message,
                    java.util.Map.of("type", type, "attendanceId", target.getId().toString()));
        }
    }
}
