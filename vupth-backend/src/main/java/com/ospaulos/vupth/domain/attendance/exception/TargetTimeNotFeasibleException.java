package com.ospaulos.vupth.domain.attendance.exception;

import java.time.OffsetDateTime;

/**
 * Intencao de fila com horario-alvo (item #40). Lancada quando o cliente pede um
 * horario-alvo (targetTime) mas a fila ja preve atender em cima ou depois desse alvo —
 * isto e, nao ha margem para "segurar a vez" como intencao. Nesse caso o cliente deve
 * apenas entrar na fila normalmente.
 *
 * Mapeada para HTTP 422 com code QUEUE_TARGET_NOT_FEASIBLE.
 */
public class TargetTimeNotFeasibleException extends RuntimeException {
    public TargetTimeNotFeasibleException(OffsetDateTime target) {
        super("Horario-alvo " + target + " inviavel: a fila ja preve atendimento em cima ou depois desse horario. "
                + "Entre na fila normalmente (sem horario-alvo).");
    }
}
