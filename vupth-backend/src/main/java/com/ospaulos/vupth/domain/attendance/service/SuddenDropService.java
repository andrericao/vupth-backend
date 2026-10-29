package com.ospaulos.vupth.domain.attendance.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.api.queue.swap.dto.response.SwapRequestResponse;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceInvalidStatusException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceNotFoundException;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.queue.exception.ClientNotInQueueException;
import com.ospaulos.vupth.domain.queue.swap.service.QueueSwapService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Queda brusca da estimativa (DEC-128, Andre 29/10): depois do aviso {@code SUDDEN_DROP} o cliente toca, ve o horario e responde.
 * <ul>
 *   <li><b>ok</b> ({@link #acknowledge}): aceita o horario novo, que passa a ser o prometido (vale a tolerancia de 10 min de sempre),
 *       e a janela de 20 min sem multa se encerra.</li>
 *   <li><b>preciso de mais tempo</b> ({@link #needMoreTime}): as 4 opcoes, todas sem multa dentro da janela: trocar, passar a vez
 *       (o proximo precisa aceitar), sair da fila e ir para o final.</li>
 *   <li><b>passar a vez</b> ({@link #passTurn}): proposta de troca com o PROXIMO da fila; sem o aceite dele nada muda e o cliente
 *       escolhe outra opcao.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SuddenDropService {

    private final AttendanceRepository attendanceRepository;
    private final EarlierTurnService earlierTurnService;
    private final QueueSwapService swapService;

    public record Option(
            @JsonProperty("action") String action,
            @JsonProperty("method") String method,
            @JsonProperty("path") String path,
            @JsonProperty("needs_acceptance") boolean needsAcceptance) {
    }

    public record Options(
            @JsonProperty("attendance_id") UUID attendanceId,
            @JsonProperty("window_until") OffsetDateTime windowUntil,
            @JsonProperty("options") List<Option> options) {
    }

    /** "Ok": aceita o horario novo e fecha a janela. Devolve o horario prometido. */
    public OffsetDateTime acknowledge(UUID attendanceId, UUID clientId) {
        Attendance attendance = ownAttendance(attendanceId, clientId);
        if (attendance.getSuddenDropAt() == null) {
            throw new AttendanceInvalidStatusException("Nao ha queda de estimativa aberta para este atendimento");
        }
        OffsetDateTime promised = earlierTurnService.acceptEarlierTurn(attendanceId, clientId);
        attendance.setSuddenDropAt(null); // regras normais a partir daqui
        attendanceRepository.save(attendance);
        log.info("[SuddenDrop] ok — attendance={} client={} prometido={}", attendanceId, clientId, promised);
        return promised;
    }

    /** "Preciso de mais tempo": as opcoes, so enquanto a janela esta aberta. */
    @Transactional(readOnly = true)
    public Options needMoreTime(UUID attendanceId, UUID clientId) {
        Attendance attendance = ownAttendance(attendanceId, clientId);
        if (!attendance.isSuddenDropNoticeOpen(OffsetDateTime.now())) {
            throw new AttendanceInvalidStatusException("A janela da queda de estimativa ja se encerrou");
        }
        String base = "/attendances/" + attendanceId;
        return new Options(attendanceId, attendance.getSuddenDropWindowUntil(), List.of(
                new Option("SWAP", "POST", "/queue/swap-requests", true),
                new Option("PASS_TURN", "POST", base + "/sudden-drop/pass-turn", true),
                new Option("LEAVE_QUEUE", "DELETE", base + "/leave-queue", false),
                new Option("MOVE_TO_END", "PATCH", base + "/move-to-end", false)));
    }

    /** "Passar a vez": propoe a troca com o proximo da fila; ele aceita ou nada muda. */
    public SwapRequestResponse passTurn(UUID attendanceId, UUID clientId) {
        Attendance attendance = ownAttendance(attendanceId, clientId);
        if (!attendance.isSuddenDropNoticeOpen(OffsetDateTime.now())) {
            throw new AttendanceInvalidStatusException("A janela da queda de estimativa ja se encerrou");
        }
        List<Attendance> queue = attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(
                attendance.getProfessional().getId(), attendance.getEstablishment().getId());
        UUID next = null;
        for (int i = 0; i < queue.size() - 1; i++) {
            if (queue.get(i).getId().equals(attendanceId)) {
                next = queue.get(i + 1).getId();
                break;
            }
        }
        if (next == null) {
            throw new ClientNotInQueueException(attendanceId); // ninguem depois dele para passar a vez
        }
        log.info("[SuddenDrop] passar a vez — attendance={} proximo={}", attendanceId, next);
        return swapService.propose(attendanceId, next, clientId);
    }

    private Attendance ownAttendance(UUID attendanceId, UUID clientId) {
        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));
        if (attendance.getClient() == null || !attendance.getClient().getId().equals(clientId)) {
            throw new EstablishmentAccessDeniedException("You can only answer the new time for your own queue position.");
        }
        if (attendance.getStatus() != AttendanceStatus.SCHEDULED && attendance.getStatus() != AttendanceStatus.LATE) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.SCHEDULED);
        }
        return attendance;
    }
}
