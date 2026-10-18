package com.ospaulos.vupth.domain.client.service;

import com.ospaulos.vupth.api.client.dto.response.PenaltyChargeResponse;
import com.ospaulos.vupth.api.client.dto.response.PendingPenaltiesResponse;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent;
import com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cobranca da multa no PROXIMO atendimento (D4 — Andre 17/09 e 25/09).
 *
 * <p>Tres estados, todos derivados de {@code chargedAttendanceId} e do status desse atendimento —
 * nenhum caminho de saida precisa lembrar de devolver a multa:
 * <ul>
 *   <li><b>pendente</b>: sem atendimento, ou o atendimento que a carregava terminou sem servico
 *       (desistiu, foi retirado, nao compareceu);</li>
 *   <li><b>em cobranca</b>: carregada por um atendimento ainda na fila ou em curso;</li>
 *   <li><b>paga</b>: o atendimento que a carregava foi concluido.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ClientPenaltyService {

    private final ClientPenaltyEventRepository penaltyEventRepository;
    private final AttendanceRepository attendanceRepository;
    private final com.ospaulos.vupth.domain.client.repository.PenaltyPaymentRepository penaltyPaymentRepository;

    /** limite-de-duas-multas (DEC-83): com tantas multas devidas, o cliente so marca depois de quitar. */
    @org.springframework.beans.factory.annotation.Value("${vupth.penalty.max-pending:2}")
    private int maxPending = 2;

    @Transactional(readOnly = true)
    public PendingPenaltiesResponse pending(UUID clientId) {
        List<PenaltyChargeResponse> penalties = describe(pendingEvents(clientId));
        BigDecimal total = penalties.stream()
                .map(PenaltyChargeResponse::amount)
                .reduce(new BigDecimal("0.00"), BigDecimal::add);
        return new PendingPenaltiesResponse(total, penalties);
    }

    /**
     * Na entrada na fila (qualquer loja), o atendimento passa a carregar as multas pendentes do
     * cliente. Convidado nao tem conta, nem multa.
     */
    public void chargeOnIngress(Attendance attendance) {
        if (attendance.getClient() == null) {
            return;
        }
        List<ClientPenaltyEvent> pending = pendingEvents(attendance.getClient().getId());
        if (pending.isEmpty()) {
            return;
        }
        pending.forEach(event -> {
            event.setChargedAttendanceId(attendance.getId());
            event.setPenaltyPaymentId(null); // um pagamento avulso vencido nao segura mais a multa
        });
        penaltyEventRepository.saveAll(pending);
        log.info("[Penalty] {} multa(s) pendente(s) do client={} passam a ser cobradas no attendance={}",
                pending.size(), attendance.getClient().getId(), attendance.getId());
    }

    /** Multas carregadas por cada atendimento — a decomposicao do relatorio. */
    @Transactional(readOnly = true)
    public Map<UUID, List<PenaltyChargeResponse>> chargedTo(Collection<UUID> attendanceIds) {
        if (attendanceIds.isEmpty()) {
            return Map.of();
        }
        List<ClientPenaltyEvent> events = penaltyEventRepository.findByChargedAttendanceIdInOrderByCreatedAtAsc(attendanceIds);
        Map<UUID, UUID> chargedByPenalty = events.stream()
                .collect(Collectors.toMap(ClientPenaltyEvent::getId, ClientPenaltyEvent::getChargedAttendanceId));
        return describe(events).stream()
                .collect(Collectors.groupingBy(p -> chargedByPenalty.get(p.penaltyId())));
    }

    /**
     * As multas devidas e ainda nao confirmadas: as pendentes mais as cobertas por um pagamento avulso vigente que o
     * painel ainda nao confirmou. Nao entram as carregadas por um corte na fila (ja estao sendo cobradas la).
     */
    @Transactional(readOnly = true)
    public List<ClientPenaltyEvent> dueFor(UUID clientId) {
        List<ClientPenaltyEvent> due = new java.util.ArrayList<>(pendingEvents(clientId));
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        Set<UUID> unconfirmed = penaltyPaymentRepository
                .findByClientIdAndStatus(clientId, com.ospaulos.vupth.domain.client.model.PenaltyPayment.Status.PENDING)
                .stream().filter(p -> p.getExpiresAt().isAfter(now))
                .map(com.ospaulos.vupth.domain.client.model.PenaltyPayment::getId).collect(Collectors.toSet());
        if (!unconfirmed.isEmpty()) {
            penaltyEventRepository.findByClientIdAndAmountIsNotNullOrderByCreatedAtAsc(clientId).stream()
                    .filter(e -> e.getAmount().signum() > 0)
                    .filter(e -> e.getPenaltyPaymentId() != null && unconfirmed.contains(e.getPenaltyPaymentId()))
                    .forEach(due::add);
        }
        return due;
    }

    /** O portao de entrar na fila ou agendar: com o limite de multas devidas, 409 {@code PENALTY_LIMIT_REACHED}. */
    @Transactional(readOnly = true)
    public void assertWithinLimit(UUID clientId) {
        List<ClientPenaltyEvent> due = dueFor(clientId);
        if (due.size() < maxPending) {
            return;
        }
        BigDecimal total = due.stream().map(ClientPenaltyEvent::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        UUID paymentId = penaltyPaymentRepository
                .findByClientIdAndStatus(clientId, com.ospaulos.vupth.domain.client.model.PenaltyPayment.Status.PENDING)
                .stream().filter(p -> p.getExpiresAt().isAfter(now))
                .max(java.util.Comparator.comparing(com.ospaulos.vupth.domain.client.model.PenaltyPayment::getCreatedAt))
                .map(com.ospaulos.vupth.domain.client.model.PenaltyPayment::getId).orElse(null);
        throw new com.ospaulos.vupth.domain.client.exception.PenaltyLimitException(
                maxPending, total, describe(due), paymentId);
    }

    /** As multas ainda a pagar do cliente, sem as que um pagamento avulso (vigente ou pago) ja cobre. */
    @Transactional(readOnly = true)
    public List<ClientPenaltyEvent> pendingFor(UUID clientId) {
        return pendingEvents(clientId);
    }

    private List<ClientPenaltyEvent> pendingEvents(UUID clientId) {
        List<ClientPenaltyEvent> withAmount = penaltyEventRepository.findByClientIdAndAmountIsNotNullOrderByCreatedAtAsc(clientId);
        Set<UUID> chargedIds = withAmount.stream()
                .map(ClientPenaltyEvent::getChargedAttendanceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<UUID> released = new HashSet<>();
        attendanceRepository.findAllById(chargedIds).forEach(a -> {
            if (a.getStatus().leftWithoutService()) {
                released.add(a.getId());
            }
        });
        // pagar-multa-avulso: a multa coberta por um pagamento avulso vigente ou pago nao volta a ser cobrada
        Set<UUID> paymentIds = withAmount.stream()
                .map(ClientPenaltyEvent::getPenaltyPaymentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        Set<UUID> held = paymentIds.isEmpty() ? Set.of() : penaltyPaymentRepository.findAllById(paymentIds).stream()
                .filter(p -> p.holds(now))
                .map(com.ospaulos.vupth.domain.client.model.PenaltyPayment::getId)
                .collect(Collectors.toSet());
        return withAmount.stream()
                .filter(e -> e.getPenaltyPaymentId() == null || !held.contains(e.getPenaltyPaymentId()))
                .filter(e -> e.getAmount().signum() > 0)
                .filter(e -> e.getChargedAttendanceId() == null || released.contains(e.getChargedAttendanceId()))
                .toList();
    }

    private List<PenaltyChargeResponse> describe(List<ClientPenaltyEvent> events) {
        Set<UUID> originIds = events.stream()
                .map(ClientPenaltyEvent::getAttendanceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, Attendance> origins = attendanceRepository.findAllById(originIds).stream()
                .collect(Collectors.toMap(Attendance::getId, Function.identity()));
        return events.stream().map(event -> {
            Attendance origin = event.getAttendanceId() != null ? origins.get(event.getAttendanceId()) : null;
            return new PenaltyChargeResponse(
                    event.getId(),
                    event.getType(),
                    event.getAmount(),
                    event.getAttendanceId(),
                    origin != null ? origin.getScheduledTime() : event.getCreatedAt(),
                    origin != null && origin.getProfessional() != null ? origin.getProfessional().getName() : null,
                    origin != null && origin.getEstablishment() != null ? origin.getEstablishment().getName() : null);
        }).toList();
    }
}
