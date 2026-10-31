package com.ospaulos.vupth.domain.attendance.service;

import com.ospaulos.vupth.api.websocket.dto.QueueNotification;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceInvalidStatusException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceNotFoundException;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.queue.exception.ClientNotInQueueException;
import com.ospaulos.vupth.domain.queue.service.ClientWaitCalculator;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import com.ospaulos.vupth.infrastructure.websocket.QueueWebSocketPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Quem sobe na fila por desistencia de outro nao e multado por atraso — a nao ser que aceite o novo
 * horario (Andre 17/09, D8 do Client; principio do B9).
 *
 * <p>A protecao ja vem do contador de atrasos: o atraso conta a partir do {@code promisedTime} gravado
 * na entrada, que a fila andando nao muda. Aqui ficam as duas pontas que faltavam: <b>avisar</b> quem
 * foi adiantado ("um cliente desistiu — topa chegar as HH:MM?") e <b>aceitar</b> o horario novo, que
 * passa a ser o prometido — e volta a valer para o atraso.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class EarlierTurnService {

    /** Adiantamento minimo para valer o aviso — abaixo disso e ruido do ritmo da fila. */
    static final int MIN_ADVANCE_MINUTES = 5;
    /** So avisa quem entrou na zona de multa (menos de 1h para a vez), como no pedido de 17/09. */
    static final int PENALTY_ZONE_MINUTES = 60;

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final AttendanceRepository attendanceRepository;
    private final ClientWaitCalculator clientWaitCalculator;
    private final QueueWebSocketPublisher queuePublisher;
    private final PushNotificationPublisher pushPublisher;

    /**
     * Depois de uma desistencia: avisa cada cliente da fila cuja vez adiantou pelo menos
     * {@link #MIN_ADVANCE_MINUTES} em relacao ao prometido e que ficou a menos de 1h. Best-effort.
     */
    public void offerEarlierTurns(UUID professionalId, UUID establishmentId) {
        OffsetDateTime now = OffsetDateTime.now();
        for (Attendance waiting : attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(professionalId, establishmentId)) {
            if (waiting.getClient() == null || waiting.getPromisedTime() == null || waiting.isIntent()) {
                continue;
            }
            if (waiting.isSuddenDropNoticeOpen(now)) {
                continue; // DEC-128: a queda brusca tem o aviso proprio (SUDDEN_DROP), que substitui este
            }
            OptionalInt wait = clientWaitCalculator.clientWaitMinutes(waiting, now);
            if (wait.isEmpty() || wait.getAsInt() >= PENALTY_ZONE_MINUTES) {
                continue;
            }
            OffsetDateTime newTime = now.plusMinutes(wait.getAsInt());
            if (newTime.isAfter(waiting.getPromisedTime().minusMinutes(MIN_ADVANCE_MINUTES))) {
                continue;
            }
            notifyEarlierTurn(waiting, newTime);
        }
    }

    /** O cliente topa o horario novo: ele vira o prometido, e o atraso volta a contar a partir dele. */
    public OffsetDateTime acceptEarlierTurn(UUID attendanceId, UUID clientId) {
        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));
        if (attendance.getClient() == null || !attendance.getClient().getId().equals(clientId)) {
            throw new EstablishmentAccessDeniedException("You can only accept a new time for your own queue position.");
        }
        if (attendance.getStatus() != AttendanceStatus.SCHEDULED && attendance.getStatus() != AttendanceStatus.LATE) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.SCHEDULED);
        }
        OffsetDateTime now = OffsetDateTime.now();
        int wait = clientWaitCalculator.clientWaitMinutes(attendance, now)
                .orElseThrow(() -> new ClientNotInQueueException(attendanceId));
        OffsetDateTime newTime = now.plusMinutes(wait);
        attendance.setPromisedTime(newTime);
        attendanceRepository.save(attendance);
        log.info("[EarlierTurn] client={} aceitou o horario novo {} (attendance={})", clientId, newTime, attendanceId);
        return newTime;
    }

    private void notifyEarlierTurn(Attendance waiting, OffsetDateTime newTime) {
        UUID clientId = waiting.getClient().getId();
        String hhmm = newTime.atZoneSameInstant(BusinessClock.zone()).format(HH_MM);
        String message = "Um cliente desistiu — sua vez adiantou para as " + hhmm + ". Topa chegar nesse horário?";
        queuePublisher.notifyClient(clientId, QueueNotification.of(
                "EARLIER_TURN_OFFERED", waiting.getId(), waiting.getEstablishment().getId(), message));
        pushPublisher.notifyUser(clientId, "Sua vez adiantou", message, Map.of(
                "type", "EARLIER_TURN_OFFERED",
                "attendanceId", String.valueOf(waiting.getId()),
                "establishmentId", String.valueOf(waiting.getEstablishment().getId()),
                "newTime", newTime.toString(),
                "promisedTime", waiting.getPromisedTime().toString()));
        log.info("[EarlierTurn] aviso ao client={}: vez adiantou de {} para {}", clientId, waiting.getPromisedTime(), newTime);
    }
}
