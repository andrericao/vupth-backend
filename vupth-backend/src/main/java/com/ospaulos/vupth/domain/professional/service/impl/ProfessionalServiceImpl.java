package com.ospaulos.vupth.domain.professional.service.impl;

import com.ospaulos.vupth.api.attendance.dto.request.AttendanceGuestRequest;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInProgressResponse;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;
import com.ospaulos.vupth.api.queue.dto.response.QueueResponse;
import com.ospaulos.vupth.api.serviceItem.dto.request.ServiceItemRequest;
import com.ospaulos.vupth.api.serviceItem.dto.response.ServiceItemResponse;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceAlreadyInProgressException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceInvalidStatusException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceNotFoundException;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.attendance.service.AttendanceService;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.service.EstablishmentOpenStatusService;
import com.ospaulos.vupth.domain.establishment.service.EstablishmentService;
import com.ospaulos.vupth.domain.professional.exception.NotAProfessionalException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.service.ProfessionalService;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;
import com.ospaulos.vupth.domain.queue.service.QueueService;
import com.ospaulos.vupth.domain.serviceItem.exception.ServiceItemAlreadyExistsException;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.serviceItem.service.ServiceItemService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@AllArgsConstructor
@org.springframework.transaction.annotation.Transactional
public class ProfessionalServiceImpl implements ProfessionalService {

    private final QueueService queueService;
    private final AttendanceService attendanceService;
    private final ProfessionalIntervalService intervalService;
    private final ServiceItemService serviceItemService;
    private final EstablishmentService establishmentService;
    private final ProfessionalRepository professionalRepository;
    private final AttendanceRepository attendanceRepository;
    private final EstablishmentRepository establishmentRepository;
    private final EstablishmentOpenStatusService openStatusService;
    private final com.ospaulos.vupth.domain.client.service.LateArrivalService lateArrivalService;

    // ─────────────────────────────────────────────────────────────
    // GUEST
    // ─────────────────────────────────────────────────────────────

    @Override
    public QueueResponse createAndAddGuestAttendanceInQueue(AttendanceGuestRequest request) {
        log.info("[Professional] createAndAddGuestAttendanceInQueue — professional={} establishment={}",
                request.professionalId(), request.establishmentId());

        Professional professional = professionalRepository.findByIdWithBaseUser(request.professionalId())
                .orElseThrow(() -> new ProfessionalNotFoundException(request.professionalId()));

        Establishment establishment = establishmentRepository.findById(request.establishmentId())
                .orElseThrow(() -> new EstablishmentNotFoundException(request.establishmentId()));

        // Bug fix: was != (reference) — agora .equals()
        if (!establishment.getId().equals(request.establishmentId())) {
            log.warn("[Professional] createAndAddGuestAttendanceInQueue — professional={} does not belong to establishment={}",
                    request.professionalId(), request.establishmentId());
            throw new EstablishmentAccessDeniedException(request.professionalId(), request.establishmentId());
        }

        Attendance attendance = attendanceService.createGuestAttendance(request, professional, establishment);
        log.info("[Professional] createAndAddGuestAttendanceInQueue — guest added attendanceId={}", attendance.getId());

        return queueService.enqueueGuestAttendance(attendance, professional);
    }

    // ─────────────────────────────────────────────────────────────
    // QUEUE QUERY
    // ─────────────────────────────────────────────────────────────

    @Override
    public QueueResponse getProfessionalQueue(UUID professionalId, UUID establishmentId) {
        log.info("[Professional] getProfessionalQueue — professional={} establishment={}",
                professionalId, establishmentId);

        Professional professional = professionalRepository.findByIdWithBaseUser(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        Establishment establishment = professional.getEstablishment();

        if (!establishment.getId().equals(establishmentId)) {
            log.warn("[Professional] getProfessionalQueue — professional={} does not belong to establishment={}",
                    professionalId, establishmentId);
            throw new EstablishmentAccessDeniedException(professionalId, establishmentId);
        }

        return queueService.getQueueOverview(professional, establishment);
    }

    // ─────────────────────────────────────────────────────────────
    // ATTENDANCE LIFECYCLE
    // ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public QueueResponse startAttendance(UUID professionalId, UUID attendanceId) {
        log.info("[Professional] startAttendance — professional={} attendance={}",
                professionalId, attendanceId);

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        Establishment establishment = attendance.getEstablishment();
        Professional professional = professionalRepository.findByIdWithBaseUser(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        assertProfessionalOwnsAttendance(professional, attendance);

        // Aceita SCHEDULED ou LATE (item #19): o cliente atrasado pode ser atendido direto ao chegar.
        if (attendance.getStatus() != AttendanceStatus.SCHEDULED
                && attendance.getStatus() != AttendanceStatus.LATE) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.SCHEDULED);
        }

        if (professional.getCurrentAttendance().isPresent()) {
            log.warn("[Professional] startAttendance — professional={} already has attendance IN_PROGRESS", professionalId);
            throw new AttendanceAlreadyInProgressException(professionalId);
        }

        // Encerrar ao atender (spec #11e): a refeicao em curso termina ao iniciar o atendimento.
        intervalService.finishActiveIntervalsOnAttend(professionalId);

        attendanceService.startAttendance(attendance, professional);
        queueService.updateQueueEstimates(professional);

        log.info("[Professional] startAttendance — attendance={} iniciado", attendanceId);
        return queueService.getQueueOverview(professional, establishment);
    }

    @Override
    @Transactional
    public QueueResponse markAttendanceLate(UUID professionalId, UUID attendanceId) {
        log.info("[Professional] markAttendanceLate — professional={} attendance={}",
                professionalId, attendanceId);

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        Establishment establishment = attendance.getEstablishment();
        Professional professional = professionalRepository.findByIdWithBaseUser(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        assertProfessionalOwnsAttendance(professional, attendance);

        // So um cliente que esta esperando pode ser marcado como atrasado.
        // Aceita SCHEDULED ou ja-LATE (marcar de novo = cai mais uma posicao).
        if (attendance.getStatus() != AttendanceStatus.SCHEDULED
                && attendance.getStatus() != AttendanceStatus.LATE) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.SCHEDULED);
        }

        // Cai uma posicao: reposiciona atras do proximo da fila (empurrando o scheduledTime).
        // A fila ja inclui SCHEDULED+LATE ordenada por scheduledTime. Se ja for o ultimo, so vira LATE.
        List<Attendance> waiting = attendanceRepository
                .findWaitingQueueByProfessionalAndEstablishment(professional.getId(), establishment.getId());
        repositionBehindNext(attendance, waiting);
        attendance.setStatus(AttendanceStatus.LATE);
        attendanceRepository.save(attendance);

        // D4: conta o atraso so depois da tolerancia (soma entre atendimentos; o 3o gera a multa) e avisa.
        // A marcacao em si segue livre (#19): antes da tolerancia o cliente cai de posicao sem contar.
        int lateNumber = lateArrivalService.registerLate(attendance, professional, java.time.OffsetDateTime.now());

        // Andre, 26/09: no 3o atraso, logo depois do registro, ele sai da vez e vai para o FIM da fila —
        // pode continuar ali ou sair (sem nova multa: ja pagou a do 3o). Antes do 3o so troca com o proximo.
        if (lateNumber >= com.ospaulos.vupth.domain.client.service.LateArrivalService.LATES_PER_PENALTY) {
            moveToEnd(attendance, attendanceRepository
                    .findWaitingQueueByProfessionalAndEstablishment(professional.getId(), establishment.getId()));
            // Recomeca no fim como espera comum: a vez atrasada acabou, e ele pode sair (leave-queue so
            // aceita SCHEDULED) ou continuar.
            attendance.setStatus(AttendanceStatus.SCHEDULED);
            attendanceRepository.save(attendance);
        }

        queueService.updateQueueEstimates(professional);
        log.info("[Professional] markAttendanceLate — attendance={} marcado LATE (caiu de posicao)", attendanceId);
        return queueService.getQueueOverview(professional, establishment);
    }

    @Override
    @Transactional(readOnly = true)
    public com.ospaulos.vupth.api.attendance.dto.response.NoShowStatusResponse noShowStatus(UUID userId, UUID attendanceId) {
        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));
        Establishment establishment = attendance.getEstablishment();
        Professional professional = attendance.getProfessional();
        assertCanMarkNoShow(userId, establishment, professional);
        List<Attendance> waiting = attendanceRepository
                .findWaitingQueueByProfessionalAndEstablishment(professional.getId(), establishment.getId());
        int wait = lateArrivalService.noShowWaitMinutes();
        boolean first = !waiting.isEmpty() && waiting.get(0).getId().equals(attendanceId);
        if (!first) {
            return new com.ospaulos.vupth.api.attendance.dto.response.NoShowStatusResponse(
                    attendanceId, false, false, "NOT_FIRST", null, null, wait);
        }
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        java.time.OffsetDateTime availableAt = lateArrivalService.noShowAvailableAt(attendance, professional, now);
        long left = Math.max(0, java.time.Duration.between(now, availableAt).getSeconds());
        boolean can = left == 0
                && (attendance.getStatus() == AttendanceStatus.SCHEDULED || attendance.getStatus() == AttendanceStatus.LATE);
        return new com.ospaulos.vupth.api.attendance.dto.response.NoShowStatusResponse(
                attendanceId, true, can, can ? null : "TOO_EARLY", availableAt, left, wait);
    }

    private static void assertCanMarkNoShow(UUID userId, Establishment establishment, Professional professional) {
        boolean isProfessional = professional.getId().equals(userId);
        boolean isOwner = establishment.getOwner() != null && establishment.getOwner().getId().equals(userId);
        if (!isProfessional && !isOwner) {
            throw new com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException(
                    "Only the professional of the attendance or the owner of the establishment can mark a no-show.");
        }
    }

    @Override
    @Transactional
    public QueueResponse markNoShow(UUID userId, UUID attendanceId) {
        log.info("[Professional] markNoShow — user={} attendance={}", userId, attendanceId);

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));
        Establishment establishment = attendance.getEstablishment();
        Professional professional = attendance.getProfessional();

        boolean isProfessional = professional.getId().equals(userId);
        boolean isOwner = establishment.getOwner() != null && establishment.getOwner().getId().equals(userId);
        if (!isProfessional && !isOwner) {
            throw new com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException(
                    "Only the professional of the attendance or the owner of the establishment can mark a no-show.");
        }
        if (attendance.getStatus() != AttendanceStatus.SCHEDULED && attendance.getStatus() != AttendanceStatus.LATE) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.SCHEDULED);
        }

        List<Attendance> waiting = attendanceRepository
                .findWaitingQueueByProfessionalAndEstablishment(professional.getId(), establishment.getId());
        if (waiting.isEmpty() || !waiting.get(0).getId().equals(attendanceId)) {
            throw com.ospaulos.vupth.domain.attendance.exception.NoShowException.notFirst();
        }
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        java.time.OffsetDateTime availableAt = lateArrivalService.noShowAvailableAt(attendance, professional, now);
        if (now.isBefore(availableAt)) {
            throw com.ospaulos.vupth.domain.attendance.exception.NoShowException.tooEarly(availableAt);
        }

        // DEC-86: nao comparecer e um atraso — troca de lugar com o proximo e soma no contador; no 3o, a multa.
        // Mesmo caminho do /late; a espera de 10 min acima passa da tolerancia de 5, entao o atraso sempre conta.
        log.info("[Professional] markNoShow — attendance={} vira atraso", attendanceId);
        return markAttendanceLate(professional.getId(), attendanceId);
    }

    @Override
    @Transactional
    public QueueResponse undoAttendanceLate(UUID professionalId, UUID attendanceId) {
        log.info("[Professional] undoAttendanceLate — professional={} attendance={}",
                professionalId, attendanceId);

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        Establishment establishment = attendance.getEstablishment();
        Professional professional = professionalRepository.findByIdWithBaseUser(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        assertProfessionalOwnsAttendance(professional, attendance);

        if (attendance.getStatus() != AttendanceStatus.LATE) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.LATE);
        }

        // Volta a SCHEDULED mantendo a posicao atual (a que ele caiu) — nao reposiciona.
        attendance.setStatus(AttendanceStatus.SCHEDULED);
        attendanceRepository.save(attendance);

        // D4: o atraso foi engano — sai do contador (e a multa que ele gerou, se ainda nao cobrada).
        lateArrivalService.undoLate(attendance);

        queueService.updateQueueEstimates(professional);
        log.info("[Professional] undoAttendanceLate — attendance={} voltou para SCHEDULED", attendanceId);
        return queueService.getQueueOverview(professional, establishment);
    }

    /**
     * Empurra {@code target} para tras do proximo da fila de espera (cai 1 posicao), ajustando o
     * {@code scheduledTime} para um instante entre o proximo e o seguinte. Se o target ja for o
     * ultimo (ou nao estiver na lista), nada muda — permanece onde esta. A lista vem ordenada por
     * {@code scheduledTime} ASC e inclui SCHEDULED+LATE.
     */
    /** Leva {@code target} para depois do ultimo da fila de espera (3o atraso — Andre, 26/09). */
    private void moveToEnd(Attendance target, List<Attendance> waiting) {
        Attendance last = waiting.isEmpty() ? null : waiting.get(waiting.size() - 1);
        if (last == null || last.getId().equals(target.getId())) {
            return; // ja e o ultimo
        }
        target.setScheduledTime(last.getScheduledTime().plusMinutes(1));
    }

    private void repositionBehindNext(Attendance target, List<Attendance> waiting) {
        int idx = -1;
        for (int i = 0; i < waiting.size(); i++) {
            if (waiting.get(i).getId().equals(target.getId())) {
                idx = i;
                break;
            }
        }
        if (idx < 0 || idx == waiting.size() - 1) {
            return; // fora da fila ou ja e o ultimo
        }

        java.time.OffsetDateTime next = waiting.get(idx + 1).getScheduledTime();
        java.time.OffsetDateTime newTime;
        if (idx + 2 < waiting.size()) {
            java.time.OffsetDateTime afterNext = waiting.get(idx + 2).getScheduledTime();
            long gapSeconds = java.time.Duration.between(next, afterNext).getSeconds();
            // ponto medio entre o proximo e o seguinte -> cai exatamente 1 posicao
            newTime = next.plusSeconds(Math.max(1, gapSeconds / 2));
        } else {
            newTime = next.plusMinutes(1); // passa a ser o ultimo
        }
        target.setScheduledTime(newTime);
    }

    // ─────────────────────────────────────────────────────────────
    // ENCAIXE (item #38)
    // ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public QueueResponse fitInAttendance(UUID professionalId, UUID attendanceId, int aheadCount) {
        log.info("[Professional] fitInAttendance — professional={} attendance={} aheadCount={}",
                professionalId, attendanceId, aheadCount);

        if (aheadCount < 0) {
            throw new IllegalArgumentException("aheadCount cannot be negative");
        }

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        Establishment establishment = attendance.getEstablishment();
        Professional professional = professionalRepository.findByIdWithBaseUser(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        assertProfessionalOwnsAttendance(professional, attendance);

        // So um cliente que esta esperando pode ser encaixado (aceita SCHEDULED ou LATE).
        if (attendance.getStatus() != AttendanceStatus.SCHEDULED
                && attendance.getStatus() != AttendanceStatus.LATE) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.SCHEDULED);
        }

        List<Attendance> waiting = attendanceRepository
                .findWaitingQueueByProfessionalAndEstablishment(professional.getId(), establishment.getId());

        // Reposiciona o atendimento para logo apos `aheadCount` OUTROS clientes e grava o
        // "length inicial" do ramp (= quantos realmente ficaram a frente). Volta a SCHEDULED
        // (sai de LATE se estava) — o ramp e marcado por isFit, nao pelo status.
        int aheadLength = repositionForFit(attendance, waiting, aheadCount);
        attendance.setStatus(AttendanceStatus.SCHEDULED);
        attendance.setFit(true);
        attendance.setFitAheadLength(aheadLength);
        attendanceRepository.save(attendance);

        queueService.updateQueueEstimates(professional);
        log.info("[Professional] fitInAttendance — attendance={} encaixado apos {} clientes (ramp length={})",
                attendanceId, aheadLength, aheadLength);
        return queueService.getQueueOverview(professional, establishment);
    }

    @Override
    @Transactional
    public QueueResponse undoFitInAttendance(UUID professionalId, UUID attendanceId) {
        log.info("[Professional] undoFitInAttendance — professional={} attendance={}",
                professionalId, attendanceId);

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        Establishment establishment = attendance.getEstablishment();
        Professional professional = professionalRepository.findByIdWithBaseUser(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        assertProfessionalOwnsAttendance(professional, attendance);

        if (!attendance.isFit()) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.SCHEDULED);
        }

        // Para de rampar: passa a contribuir o tempo cheio, mantendo a posicao atual.
        attendance.setFit(false);
        attendance.setFitAheadLength(null);
        attendanceRepository.save(attendance);

        queueService.updateQueueEstimates(professional);
        log.info("[Professional] undoFitInAttendance — attendance={} deixou de ser encaixe", attendanceId);
        return queueService.getQueueOverview(professional, establishment);
    }

    /**
     * Posiciona {@code target} logo apos {@code aheadCount} OUTROS atendimentos da fila de espera,
     * ajustando o {@code scheduledTime} (ponto medio entre os vizinhos — mesmo padrao do #19).
     * A fila vem ordenada por {@code scheduledTime} ASC e inclui o proprio {@code target}.
     *
     * @return o "length inicial" do ramp = quantos clientes realmente ficaram a frente (clamp
     *         em [0, n_outros]); usado como divisor das fatias de tempo do encaixe.
     */
    private int repositionForFit(Attendance target, List<Attendance> waiting, int aheadCount) {
        List<Attendance> others = waiting.stream()
                .filter(a -> !a.getId().equals(target.getId()))
                .toList();
        if (others.isEmpty()) {
            return 0; // unico na fila: sem ninguem a frente, sem ramp
        }

        int idx = Math.min(aheadCount, others.size()); // quantos ficam realmente a frente
        java.time.OffsetDateTime newTime;
        if (idx == 0) {
            newTime = others.get(0).getScheduledTime().minusMinutes(1); // vai pra frente de todos
        } else if (idx >= others.size()) {
            newTime = others.get(others.size() - 1).getScheduledTime().plusMinutes(1); // vai pro fim
        } else {
            java.time.OffsetDateTime before = others.get(idx - 1).getScheduledTime();
            java.time.OffsetDateTime after = others.get(idx).getScheduledTime();
            long gapSeconds = java.time.Duration.between(before, after).getSeconds();
            newTime = before.plusSeconds(Math.max(1, gapSeconds / 2));
        }
        target.setScheduledTime(newTime);
        return idx;
    }

    @Override
    public QueueResponse finishAttendance(UUID professionalId, UUID attendanceId) {
        log.info("[Professional] finishAttendance — professional={} attendance={}",
                professionalId, attendanceId);

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        Establishment establishment = attendance.getEstablishment();
        Professional professional = professionalRepository.findByIdWithBaseUser(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        assertProfessionalOwnsAttendance(professional, attendance);

        if (!attendance.getStatus().isFinishable()) {
            throw AttendanceInvalidStatusException.notFinishable(attendance.getStatus());
        }

        attendanceService.finishAttendance(attendance);
        log.info("[Professional] finishAttendance — attendance={} finalizado (FINISHED)", attendanceId);

        // S1 fatia 3 — fim da drenagem: com a fila fechada (accepting_new=false), finalizar o
        // ULTIMO atendimento (nada mais ativo: sem fila propria nem em andamento) vira OFF sozinho
        // e recalcula o open_status derivado (mesma transacao) — sendo o ultimo presente, a loja
        // fecha. "Drena e sai sozinho" (handoff Partner S1).
        if (Boolean.FALSE.equals(professional.getAcceptingNew())
                && professional.getWorkStatus().countsAsOpen()
                && !attendanceRepository.existsByProfessional_IdAndStatusIn(professionalId, List.of(
                        AttendanceStatus.SCHEDULED, AttendanceStatus.LATE,
                        AttendanceStatus.IN_PROGRESS, AttendanceStatus.PAUSED))) {
            professional.setWorkStatus(ProfessionalStatus.OFF);
            professionalRepository.save(professional);
            if (establishment != null) {
                openStatusService.deriveFromProfessionals(establishment.getId());
            }
            log.info("[Professional] finishAttendance — drenagem concluida: professional={} auto-OFF", professionalId);
        }

        // Reload after save to reflect FINISHED status and computed duration
        Attendance finished = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        // Build snapshot with FINISHED status for inAttendance field
        AttendanceInProgressResponse finishedSnapshot = attendanceService.buildFinishedAttendanceResponse(finished);

        QueueResponse nextQueue = queueService.getQueueOverview(professional, establishment);

        return new QueueResponse(
                nextQueue.professionalId(),
                nextQueue.professionalName(),
                nextQueue.establishmentId(),
                nextQueue.establishmentName(),
                nextQueue.size(),
                nextQueue.attendancesInQueue(),
                nextQueue.totalEstimatedMinutes(),
                nextQueue.totalElapsedMinutes(),
                nextQueue.totalRemainingMinutes(),
                finishedSnapshot
        );
    }

    @Override
    public QueueResponse cancelAttendance(UUID professionalId, UUID attendanceId) {
        log.info("[Professional] cancelAttendance — professional={} attendance={}",
                professionalId, attendanceId);

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        Establishment establishment = attendance.getEstablishment();
        Professional professional = professionalRepository.findByIdWithBaseUser(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        assertProfessionalOwnsAttendance(professional, attendance);

        if (attendance.getStatus() != AttendanceStatus.SCHEDULED) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.SCHEDULED);
        }

        attendanceService.cancelAttendance(attendance);
        log.info("[Professional] cancelAttendance — attendance={} cancelado", attendanceId);

        return queueService.getQueueOverview(professional, establishment);
    }

    @Override
    public QueueResponse pauseAttendance(UUID attendanceId, UUID professionalId) {
        log.info("[Professional] pauseAttendance — professional={} attendance={}",
                professionalId, attendanceId);

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        Establishment establishment = attendance.getEstablishment();
        Professional professional = professionalRepository.findByIdWithBaseUser(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        assertProfessionalOwnsAttendance(professional, attendance);

        // Bug fix: era SCHEDULED — deve ser IN_PROGRESS para pausar
        if (attendance.getStatus() != AttendanceStatus.IN_PROGRESS) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.IN_PROGRESS);
        }

        attendanceService.pauseAttendance(attendance);
        log.info("[Professional] pauseAttendance — attendance={} pausado", attendanceId);

        return queueService.getQueueOverview(professional, establishment);
    }

    @Override
    public QueueResponse resumeAttendance(UUID attendanceId, UUID professionalId) {
        log.info("[Professional] resumeAttendance — professional={} attendance={}",
                professionalId, attendanceId);

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        Establishment establishment = attendance.getEstablishment();
        Professional professional = professionalRepository.findByIdWithBaseUser(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        assertProfessionalOwnsAttendance(professional, attendance);

        // Bug fix: era SCHEDULED — deve ser PAUSED para retomar
        if (attendance.getStatus() != AttendanceStatus.PAUSED) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.PAUSED);
        }

        attendanceService.resumeAttendance(attendance);
        log.info("[Professional] resumeAttendance — attendance={} retomado", attendanceId);

        return queueService.getQueueOverview(professional, establishment);
    }

    // ─────────────────────────────────────────────────────────────
    // SERVICE ITEMS
    // ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public ServiceItemResponse createServiceItem(UUID professionalId, ServiceItemRequest request) {
        log.info("[Professional] createServiceItem — professional={} type={} name={}",
                professionalId, request.serviceItemType(), request.customName());

        // Bug fix: era request.establishmentId() — agora usa professionalId correto
        Professional professional = professionalRepository.findByIdWithBaseUser(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        Establishment establishment = professional.getEstablishment();

        if (!establishment.getProfessionals().contains(professional)) {
            throw new EstablishmentAccessDeniedException(professionalId, establishment.getId());
        }

        List<ServiceItem> services = establishmentService.getServicesItemByEstablishment(establishment.getId());

        boolean nameExists = services.stream()
                .anyMatch(s -> s.getCustomName().equalsIgnoreCase(request.customName()));
        if (nameExists) {
            throw new ServiceItemAlreadyExistsException(request.customName());
        }

        return serviceItemService.createServiceItem(professional, request);
    }

    @Override
    @Transactional
    public ServiceItemResponse createServiceItemForEstablishment(UUID requesterId, UUID establishmentId, ServiceItemRequest request) {
        log.info("[Professional] createServiceItemForEstablishment — requester={} establishment={} type={} name={}",
                requesterId, establishmentId, request.serviceItemType(), request.customName());

        Establishment establishment = establishmentRepository.findById(establishmentId)
                .orElseThrow(() -> new EstablishmentNotFoundException(establishmentId));

        Professional requester = professionalRepository.findByIdWithBaseUser(requesterId)
                .orElseThrow(() -> new ProfessionalNotFoundException(requesterId));

        // S1: o owner OU um profissional AFILIADO a esta loja pode cadastrar servico.
        // O servico fica sob o professional do requester (ServiceItem.professional ->
        // professional.establishment = a loja), entao o afiliado cadastra o proprio servico.
        boolean isOwner = establishment.getOwner() != null
                && establishment.getOwner().getId().equals(requesterId);
        boolean isAffiliated = requester.getEstablishment() != null
                && requester.getEstablishment().getId().equals(establishmentId);
        if (!isOwner && !isAffiliated) {
            throw new EstablishmentAccessDeniedException(
                    "User " + requesterId + " is neither the owner nor an affiliated professional of establishment " + establishmentId);
        }

        List<ServiceItem> services = establishmentService.getServicesItemByEstablishment(establishmentId);
        boolean nameExists = services.stream()
                .anyMatch(s -> s.getCustomName().equalsIgnoreCase(request.customName()));
        if (nameExists) {
            throw new ServiceItemAlreadyExistsException(request.customName());
        }

        return serviceItemService.createServiceItem(requester, request);
    }

    // ─────────────────────────────────────────────────────────────
    // GUARDS
    // ─────────────────────────────────────────────────────────────

    /**
     * Verifica que:
     * 1. O professional carregado pelo requestedProfessionalId é o dono do attendance.
     * 2. O professional possui a role PROFESSIONAL ativa.
     *
     * Como o professional já foi carregado via findByIdWithBaseUser(requestedProfessionalId),
     * a guarda de ID verifica que o attendance.professional.id == requestedProfessionalId.
     */
    private void assertProfessionalOwnsAttendance(Professional professional, Attendance attendance) {
        // Verify that the professional is the one who owns this attendance
        if (!professional.getId().equals(attendance.getProfessional().getId())) {
            log.warn("[Professional] assertProfessionalOwnsAttendance — professional={} does not own attendance={}",
                    professional.getId(), attendance.getId());
            throw new EstablishmentAccessDeniedException(
                    String.format("Professional '%s' does not own attendance '%s'",
                            professional.getId(), attendance.getId()));
        }
        
        // Also verify that the professional has the PROFESSIONAL role
        if (!professional.getBaseUser().isProfessional()) {
            log.warn("[Professional] assertProfessionalOwnsAttendance — user={} does not have PROFESSIONAL role",
                    professional.getBaseUser().getId());
            throw new NotAProfessionalException(professional.getBaseUser().getId());
        }
    }
}
