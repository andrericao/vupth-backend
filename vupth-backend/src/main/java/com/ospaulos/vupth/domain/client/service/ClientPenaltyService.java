package com.ospaulos.vupth.domain.client.service;

import com.ospaulos.vupth.api.client.dto.response.PenaltyChargeResponse;
import com.ospaulos.vupth.api.client.dto.response.PendingPenaltiesResponse;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent;
import com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.PriceSplit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
        pending.forEach(event -> event.setChargedAttendanceId(attendance.getId()));
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
        return withAmount.stream()
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
            BigDecimal gatewayFee = event.getAmount().multiply(PriceSplit.INTERMEDIARY_RATE).setScale(2, RoundingMode.HALF_UP);
            return new PenaltyChargeResponse(
                    event.getId(),
                    event.getType(),
                    event.getAmount(),
                    gatewayFee,
                    event.getAmount().subtract(gatewayFee),
                    event.getAttendanceId(),
                    origin != null ? origin.getScheduledTime() : event.getCreatedAt(),
                    origin != null && origin.getProfessional() != null ? origin.getProfessional().getName() : null,
                    origin != null && origin.getEstablishment() != null ? origin.getEstablishment().getName() : null);
        }).toList();
    }
}
