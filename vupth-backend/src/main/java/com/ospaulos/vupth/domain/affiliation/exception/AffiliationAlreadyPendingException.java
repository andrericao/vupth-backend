package com.ospaulos.vupth.domain.affiliation.exception;

import java.util.UUID;

public class AffiliationAlreadyPendingException extends RuntimeException {

    public AffiliationAlreadyPendingException(UUID professionalId, UUID establishmentId) {
        super("An affiliation request is already pending for professional " +
                professionalId + " and establishment " + establishmentId + ".");
    }

    /**
     * Pendencia unica global: o profissional ja tem uma solicitacao PENDING
     * (para qualquer establishment) e nao pode solicitar a outro.
     */
    public AffiliationAlreadyPendingException(UUID professionalId) {
        super("Professional " + professionalId + " already has a pending affiliation request " +
                "and cannot request another establishment until it is resolved or cancelled.");
    }
}
