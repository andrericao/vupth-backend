package com.ospaulos.vupth.domain.client.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.client.event.ClientAbsenceEvent;
import com.ospaulos.vupth.domain.client.model.ClientAbsence;
import com.ospaulos.vupth.domain.client.repository.ClientAbsenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Tres faltas, pagar adiantado (DEC-105/115). Soma o "nao cheguei" (marcado pelo profissional) e a saida da fila (feita pelo
 * cliente); grava tambem para o cliente isento da multa (DEC-104). Com {@value #LIMIT} ocorrencias nos ultimos {@value #WINDOW_DAYS}
 * dias, e que ainda nao zerou — zera quando ele conclui um atendimento pago depois da ultima ocorrencia —, o agendamento dele sai
 * com a marca de pagamento adiantado: agendar continua livre, o cliente mostra ao profissional que pagou e o profissional confirma
 * o recebimento antes, na hora ou depois. Entrar na fila no dia nunca e barrado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ClientAbsenceService {

    public static final int LIMIT = 3;
    public static final int WINDOW_DAYS = 90;

    private final ClientAbsenceRepository repository;
    private final AttendanceRepository attendanceRepository;

    public record Status(
            @JsonProperty("absence_count") int absenceCount,
            @JsonProperty("absence_limit") int absenceLimit,
            @JsonProperty("prepay_required") boolean prepayRequired) {
    }

    @EventListener
    public void onAbsence(ClientAbsenceEvent event) {
        record(event.clientId(), event.attendanceId(), event.type());
    }

    private void record(UUID clientId, UUID attendanceId, com.ospaulos.vupth.domain.client.model.ClientAbsenceType type) {
        if (clientId == null || repository.existsByAttendanceIdAndType(attendanceId, type)) {
            return;
        }
        repository.save(new ClientAbsence(clientId, attendanceId, type, OffsetDateTime.now()));
        log.info("[Absence] ocorrencia {} — client={} attendance={}", type, clientId, attendanceId);
    }

    @Transactional(readOnly = true)
    public Status statusOf(UUID clientId) {
        OffsetDateTime since = OffsetDateTime.now().minusDays(WINDOW_DAYS);
        OffsetDateTime lastPaid = attendanceRepository.lastPaidFinishedEnd(clientId);
        if (lastPaid != null && lastPaid.isAfter(since)) {
            since = lastPaid;
        }
        int count = (int) repository.countByClientIdAndCreatedAtAfter(clientId, since);
        return new Status(count, LIMIT, count >= LIMIT);
    }

    @Transactional(readOnly = true)
    public boolean prepayRequired(UUID clientId) {
        return statusOf(clientId).prepayRequired();
    }
}
