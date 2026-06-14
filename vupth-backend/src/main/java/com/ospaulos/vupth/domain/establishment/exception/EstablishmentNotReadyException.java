package com.ospaulos.vupth.domain.establishment.exception;

import java.util.UUID;

/**
 * Lançada quando um establishment tenta abrir (open-status = OPEN) sem
 * atender às condições mínimas para ser considerado ativo.
 *
 * Regra MVP: precisa ter ao menos um profissional com ao menos um serviço.
 * Mapeada para HTTP 422 com code ESTABLISHMENT_NOT_READY.
 */
public class EstablishmentNotReadyException extends RuntimeException {
    public EstablishmentNotReadyException(UUID id) {
        super("Establishment " + id + " não pode abrir: precisa de ao menos um profissional com ao menos um serviço.");
    }
}
