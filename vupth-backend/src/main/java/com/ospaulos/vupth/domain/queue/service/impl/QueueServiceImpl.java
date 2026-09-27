package com.ospaulos.vupth.domain.queue.service.impl;

import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInProgressResponse;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;
import com.ospaulos.vupth.api.client.dto.response.ClientInQueueResponse;
import com.ospaulos.vupth.api.queue.dto.response.QueueResponse;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.attendance.service.AttendanceService;
import com.ospaulos.vupth.domain.queue.exception.ClientNotInQueueException;
import com.ospaulos.vupth.domain.queue.service.QueueService;
import com.ospaulos.vupth.domain.queue.service.ClientWaitCalculator;
import com.ospaulos.vupth.domain.queue.service.QueueUtils;
import com.ospaulos.vupth.domain.attendance.penalty.ExitPenaltyRule;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@AllArgsConstructor
@org.springframework.transaction.annotation.Transactional
public class QueueServiceImpl implements QueueService {

    // item #40: tolerancia (min) do gatilho de confirmacao da intencao. 0 = confirma exatamente quando
    // o atendimento previsto alcanca o horario-alvo. Aumentar antecipa a confirmacao (deixa de ceder antes).
    private static final int INTENT_CONFIRM_TOLERANCE_MINUTES = 0;

    private final AttendanceService attendanceService;

    private final ProfessionalRepository professionalRepository;
    private final AttendanceRepository attendanceRepository;
    private final ClientWaitCalculator clientWaitCalculator;
    private final com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository penaltyEventRepository;

    @Override
    public QueueResponse getQueueOverview(Professional professional, Establishment establishment) {

        QueueResponse queueBase = getQueueForProfessional(professional, establishment);

        Optional<Attendance> currentAttendance = professional.getCurrentAttendance();

        if (currentAttendance.isEmpty()) {
            return queueBase;
        }

        AttendanceInProgressResponse attendanceInProgressResponse =
                attendanceService.buildAttendanceInProgressResponse(currentAttendance.orElse(null));

        // Obtenha os attendances futuros (queue)
        List<Attendance> queue = professional.getScheduledAttendances();

        // Get applicable intervals
        OffsetDateTime now = OffsetDateTime.now();
        List<ProfessionalInterval> intervals = professional.getIntervals();

        int tempoRestanteAtendimentoAtual = attendanceInProgressResponse.remainingMinutes();
        int tempoRestanteFila = QueueUtils.calculateWaitingTimeForPosition(queue, intervals, now, queue.size());
        int totalRemaining = tempoRestanteAtendimentoAtual + tempoRestanteFila;

        int tempoEstimadoFila = QueueUtils.calculateWaitingTimeForPosition(queue, intervals, now, queue.size());
        int totalEstimated = currentAttendance.get().getEstimatedTotalMinutes() + tempoEstimadoFila;

        int totalElapsed = attendanceInProgressResponse.elapsedMinutes()
                + (totalEstimated - totalRemaining);

        return new QueueResponse(
                queueBase.professionalId(),
                queueBase.professionalName(),
                queueBase.establishmentId(),
                queueBase.establishmentName(),
                queueBase.size(),
                queueBase.attendancesInQueue(),
                totalEstimated,
                totalElapsed,
                totalRemaining,
                //pausedTime,
                attendanceInProgressResponse
        );
    }

    @Override
    public ClientInQueueResponse enqueueAttendance(Attendance attendance, Professional professional) {

        List<Attendance> queue = professional.getScheduledAttendances();
        queue.add(attendance);

        return queueWaitingTime(attendance);
    }

    @Override
    public QueueResponse enqueueGuestAttendance(Attendance attendance, Professional professional) {
        // O attendance já foi persistido pelo attendanceService.createGuestAttendance().
        // Não é necessário manipular a lista em memória — getQueueOverview usa query ao banco.
        return getQueueOverview(professional, attendance.getEstablishment());
    }

    @Override
    public ClientInQueueResponse queueWaitingTime(Attendance attendance) {

        Professional professional = attendance.getProfessional();
        Establishment establishment = attendance.getEstablishment();

        QueueResponse fullQueue = getQueueOverview(professional, establishment);

        int clientPosition = findClientPosition(fullQueue, attendance.getId());

        if (clientPosition == -1) {
            throw new ClientNotInQueueException(attendance.getId());
        }

        // ETA ESTAVEL do cliente (item #37 lock). A projecao meal-aware segue valendo para o snapshot
        // da loja (cliente novo decidindo), nao para o ETA de quem ja esta na fila. A conta mora no
        // ClientWaitCalculator porque a multa de saida precisa exatamente deste numero.
        OffsetDateTime now = OffsetDateTime.now();
        int totalWaitingTime = clientWaitCalculator.clientWaitMinutes(attendance, now)
                .orElseThrow(() -> new ClientNotInQueueException(attendance.getId()));
        // S10 (26/09): a espera e o horario EXIBIDOS levam a folga de -10 min; a multa de saida abaixo e o
        // horario prometido (ingresso) seguem o tempo real — ninguem e punido pela folga.
        int displayedWait = QueueUtils.displayedWait(totalWaitingTime);
        OffsetDateTime startTime = now.plusMinutes(displayedWait);

        AttendanceInQueueResponse attendanceQueue = AttendanceInQueueResponse.fromEntity(attendance);

        UUID clientId = attendance.getClient() != null ? attendance.getClient().getId() : null;

        return new ClientInQueueResponse(
                attendance.getId(),
                clientId,
                clientPosition + 1,
                attendanceQueue,
                fullQueue.size(),
                fullQueue.totalEstimatedMinutes(),
                fullQueue.totalRemainingMinutes(),
                displayedWait,
                startTime,
                attendance.getToleranceUntil(),
                // quem ja pagou a multa do 3o atraso sai sem nova multa (Andre, 26/09)
                penaltyEventRepository.existsByAttendanceIdAndType(attendance.getId(),
                        com.ospaulos.vupth.domain.client.model.PenaltyType.LATE_ARRIVAL)
                        ? new java.math.BigDecimal("0.00")
                        : ExitPenaltyRule.exitPenalty(now, attendance.getToleranceUntil(), totalWaitingTime),
                attendance.getClient() != null ? attendance.getClient().getLateCount() : 0,
                attendance.getClient() != null ? attendance.getClient().getLatesUntilPenalty()
                        : com.ospaulos.vupth.domain.client.service.LateArrivalService.LATES_PER_PENALTY,
                attendance.getPromisedTime()
        );
    }

    private QueueResponse getQueueForProfessional(Professional professional, Establishment establishment) {

        // IMPORTANTE: não usar professional.getScheduledAttendances() aqui.
        // Esse método lê professional.attendances (coleção LAZY em memória) que pode estar
        // desatualizada — especialmente logo após um save() de um novo guest attendance.
        // A query direta ao banco garante dados consistentes.
        List<Attendance> scheduledQueue = attendanceRepository
                .findWaitingQueueByProfessionalAndEstablishment(
                        professional.getId(),
                        establishment.getId()
                );

        List<AttendanceInQueueResponse> itemInfos = new ArrayList<>();

        int totalEstimated = 0;
        int totalRemaining = 0;

        for (int i = 0; i < scheduledQueue.size(); i++) {
            Attendance attendance = scheduledQueue.get(i);

            AttendanceInQueueResponse attendanceInfo = attendanceService.buildAttendanceInQueueResponse(attendance);

            // item #38: encaixe contribui tempo rampado (i = clientes a frente agora); NORMAL = tempo cheio.
            int estimatedMinutes = QueueUtils.effectiveEstimatedMinutes(attendance, i);
            totalEstimated += estimatedMinutes;
            totalRemaining += estimatedMinutes;

            itemInfos.add(attendanceInfo);
        }

        return new QueueResponse(
                professional.getId(),
                professional.getName(),
                establishment.getId(),
                establishment.getName(),
                itemInfos.size(),
                itemInfos,
                totalEstimated,
                0,
                totalRemaining,
                null
        );
    }

    @Override
    // TODO: Move this code to ProfessionalService class
    public void updateQueueEstimates(Professional professional) {

        OffsetDateTime nextAvailableTime = calculateNextAvailableTime(professional);

        // Inclui LATE (item #19): o atrasado fica na fila e precisa ser renormalizado junto,
        // mantendo a posicao relativa (senao seu horario antigo o jogaria pro fim).
        List<Attendance> queuedAttendances = professional.getWaitingAttendances();

        updateQueueScheduledTimes(queuedAttendances, nextAvailableTime);
    }

    private OffsetDateTime calculateNextAvailableTime(Professional professional) {
        return professional.getCurrentAttendance()
                .map(currentAttendance -> {
                    int remainingTime = currentAttendance.getRemainingMinutes();

                    log.info("Professional {} has is_active attendance - {} minutes remaining",
                            professional.getId(), remainingTime);

                    return OffsetDateTime.now().plusMinutes(remainingTime);
                })
                .orElseGet(() -> {
                    log.info("Professional {} available now", professional.getId());
                    return OffsetDateTime.now();
                });
    }

    private void updateQueueScheduledTimes(List<Attendance> queuedAttendances, OffsetDateTime startTime) {

        if(queuedAttendances.isEmpty()){
            log.info("No queued attendances to update!");
            return;
        }

        OffsetDateTime currentTime = startTime;

        for (Attendance attendance : queuedAttendances) {
            // item #40 (intencao de fila): uma intencao reserva um slot FIXO (scheduledTime = horario-alvo/encaixe)
            // e sorta ao fim da fila, cedendo a vez aos walk-ins. Como a lista vem ordenada por scheduledTime,
            // ao chegar aqui o currentTime ja acumulou todos os walk-ins A FRENTE — ou seja, e o horario PREVISTO
            // de atendimento desta intencao. Avaliamos a CONFIRMACAO (item #40, gatilho em evento de fila): se o
            // previsto encostou no alvo, a intencao deixa de ceder, vira SCHEDULED normal e trava no inicio da
            // fila (anchor) para nao ser cortada por futuros walk-ins. Enquanto nao confirma, mantem o slot e NAO
            // consome a timeline (currentTime nao avanca), senao empurraria os walk-ins de tras.
            if (attendance.isIntent()) {
                int etaToHere = (int) Math.max(0, Duration.between(startTime, currentTime).toMinutes());
                boolean confirm = QueueUtils.shouldConfirmIntent(
                        startTime, etaToHere, attendance.getTargetTime(), INTENT_CONFIRM_TOLERANCE_MINUTES);
                if (!confirm) {
                    log.debug("⏭️ Intent {} ainda cede (previsto={} < alvo={})",
                            attendance.getId(), currentTime, attendance.getTargetTime());
                    continue;
                }
                attendance.setIntent(false);
                attendance.setScheduledTime(startTime); // trava no inicio: futuros walk-ins (now > startTime) entram atras
                attendanceRepository.save(attendance);
                log.info("✅ Intent {} confirmada — previsto {} encostou no alvo {}",
                        attendance.getId(), currentTime, attendance.getTargetTime());
                continue;
            }

            OffsetDateTime oldTime = attendance.getScheduledTime();

            attendance.setScheduledTime(currentTime);
            currentTime = currentTime.plusMinutes(attendance.getEstimatedMinutes());

            Attendance saved = attendanceRepository.save(attendance);

            log.debug("⏰ Updated attendance {} - Old: {} → New: {}",
                    attendance.getId(), oldTime, saved.getScheduledTime());
        }

        log.info("Updated {} attendances in queue", queuedAttendances.size());
    }


        private int findClientPosition(QueueResponse queue, UUID attendanceId) {
        for (int i = 0; i < queue.size(); i++) {
            if (queue.attendancesInQueue().get(i).id().equals(attendanceId))
                return i;
        }
        return -1;
    }

}