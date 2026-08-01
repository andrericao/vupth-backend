package com.ospaulos.vupth.domain.attendance.exception;

import java.time.OffsetDateTime;

/**
 * Intencao de atendimento (backlog #4). Lancada quando o {@code desiredTime} pretendido e ANTERIOR
 * ao termino previsto dos atendimentos ja na fila viva do profissional (o "fim projetado da fila").
 * Uma intencao so faz sentido para um horario que a fila ainda nao alcancou: se a fila ja preve
 * terminar depois do horario pedido, a intencao "nasceria atrasada". O horario EXATO do fim previsto
 * e permitido (>=); apenas horarios estritamente antes sao recusados (definicao Andre 2026-07-13).
 *
 * Mapeada para HTTP 422 com code INTENTION_BEFORE_QUEUE_END.
 */
public class IntentionBeforeQueueEndException extends RuntimeException {
    public IntentionBeforeQueueEndException(OffsetDateTime desiredTime, OffsetDateTime projectedQueueEnd) {
        super("Horario pretendido " + desiredTime + " e anterior ao termino previsto dos atendimentos ("
                + projectedQueueEnd + "). Escolha um horario igual ou posterior.");
    }
}
