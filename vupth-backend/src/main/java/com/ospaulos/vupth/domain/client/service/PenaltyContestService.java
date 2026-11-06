package com.ospaulos.vupth.domain.client.service;

import com.ospaulos.vupth.domain.client.exception.PenaltyPaymentException;
import com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent;
import com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * multa-contestavel (F5, decisao do Andre 05/11): o cliente contesta uma multa pendente em ate 7 dias, com motivo. A multa contestada
 * sai do total a pagar e do teto de multas ate o ADMIN decidir: {@code UPHELD} (volta a valer) ou {@code VOIDED} (anulada). Nao ha limite de
 * contestacoes por dia: contestar nao torna o cliente imune a uma multa nova.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PenaltyContestService {

    public static final int WINDOW_DAYS = 7;
    public static final int REASON_MAX = 300;

    private final ClientPenaltyEventRepository events;
    private final ClientPenaltyService penaltyService;

    public ClientPenaltyEvent contest(UUID clientId, UUID penaltyId, String reason) {
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty() || text.length() > REASON_MAX) {
            throw new PenaltyPaymentException(HttpStatus.UNPROCESSABLE_ENTITY, "PENALTY_CONTEST_REASON_INVALID",
                    "Conte o motivo da contestacao em ate " + REASON_MAX + " caracteres.");
        }
        ClientPenaltyEvent event = events.findById(penaltyId)
                .filter(e -> e.getClient() != null && clientId.equals(e.getClient().getId()))
                .orElseThrow(() -> new PenaltyPaymentException(HttpStatus.NOT_FOUND, "PENALTY_NOT_FOUND",
                        "Multa nao encontrada."));
        if (event.getContestedAt() != null) {
            throw new PenaltyPaymentException(HttpStatus.CONFLICT, "PENALTY_ALREADY_CONTESTED",
                    "Esta multa ja foi contestada.");
        }
        if (event.getCreatedAt() != null && event.getCreatedAt().isBefore(OffsetDateTime.now().minusDays(WINDOW_DAYS))) {
            throw new PenaltyPaymentException(HttpStatus.CONFLICT, "PENALTY_CONTEST_EXPIRED",
                    "O prazo de " + WINDOW_DAYS + " dias para contestar esta multa passou.");
        }
        boolean pending = penaltyService.pendingFor(clientId).stream().anyMatch(p -> p.getId().equals(penaltyId));
        if (!pending) {
            throw new PenaltyPaymentException(HttpStatus.CONFLICT, "PENALTY_NOT_CONTESTABLE",
                    "So da para contestar uma multa pendente (nao cobrada nem paga).");
        }
        event.setContestedAt(OffsetDateTime.now());
        event.setContestReason(text);
        log.info("[Penalty] client={} contestou a multa {}", clientId, penaltyId);
        return events.save(event);
    }

    /** O ADMIN decide: {@code uphold} mantem a multa (volta ao total a pagar); senao anula. */
    public ClientPenaltyEvent resolve(UUID penaltyId, boolean uphold) {
        ClientPenaltyEvent event = events.findById(penaltyId)
                .orElseThrow(() -> new PenaltyPaymentException(HttpStatus.NOT_FOUND, "PENALTY_NOT_FOUND",
                        "Multa nao encontrada."));
        if (event.getContestedAt() == null) {
            throw new PenaltyPaymentException(HttpStatus.CONFLICT, "PENALTY_NOT_CONTESTED",
                    "Esta multa nao foi contestada.");
        }
        if (event.getResolvedAt() != null) {
            throw new PenaltyPaymentException(HttpStatus.CONFLICT, "PENALTY_ALREADY_RESOLVED",
                    "Esta contestacao ja foi decidida.");
        }
        event.setResolvedAt(OffsetDateTime.now());
        event.setResolution(uphold ? "UPHELD" : "VOIDED");
        log.info("[Penalty] multa {} contestada decidida: {}", penaltyId, event.getResolution());
        return events.save(event);
    }
}
