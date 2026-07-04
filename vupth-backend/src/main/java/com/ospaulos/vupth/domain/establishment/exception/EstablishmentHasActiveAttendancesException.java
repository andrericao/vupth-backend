package com.ospaulos.vupth.domain.establishment.exception;

import java.util.UUID;

/**
 * Lançada quando um establishment tenta fechar (open-status = CLOSED ou TEMPORARILY_CLOSED)
 * enquanto ainda há atendimento ativo ou fila de espera — o dono não pode "sumir" com a loja
 * deixando clientes na fila ou em atendimento (Partner BUG6).
 *
 * Ativo = atendimento em {@code SCHEDULED}, {@code LATE}, {@code IN_PROGRESS} ou {@code PAUSED}.
 * Mapeada para HTTP 409 com code ESTABLISHMENT_HAS_ACTIVE_ATTENDANCES.
 */
public class EstablishmentHasActiveAttendancesException extends RuntimeException {
    public EstablishmentHasActiveAttendancesException(UUID id) {
        super("Establishment " + id + " não pode fechar: há atendimento ativo ou fila de espera.");
    }
}
