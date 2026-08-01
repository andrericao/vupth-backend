package com.ospaulos.vupth.domain.attendance.exception;

import java.time.OffsetDateTime;

/**
 * Intencao de atendimento (backlog #4). Lancada quando o {@code desiredTime} pedido esta longe
 * demais no futuro — alem do horizonte permitido para manifestar intencao (ex.: 30 dias). Enquanto
 * a "agenda liberada" (dias publicados pelo profissional) nao existe, o horizonte e um teto simples.
 *
 * Mapeada para HTTP 422 com code INTENTION_HORIZON_EXCEEDED.
 */
public class IntentionHorizonExceededException extends RuntimeException {
    public IntentionHorizonExceededException(OffsetDateTime desiredTime, int horizonDays) {
        super("Horario pretendido " + desiredTime + " esta alem do horizonte de " + horizonDays
                + " dias para manifestar intencao.");
    }
}
