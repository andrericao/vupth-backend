package com.ospaulos.vupth.domain.establishment.exception;

import java.util.UUID;

/** Loja desativada ou removida: nao recebe fila, pedido de corte nem agendamento — 409 ESTABLISHMENT_UNAVAILABLE. */
public class EstablishmentUnavailableException extends RuntimeException {

    public EstablishmentUnavailableException(UUID establishmentId) {
        super("This establishment is not available: " + establishmentId);
    }
}
