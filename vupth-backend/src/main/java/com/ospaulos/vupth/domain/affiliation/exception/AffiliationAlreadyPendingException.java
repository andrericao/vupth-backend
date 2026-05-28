package com.ospaulos.vupth.domain.affiliation.exception;

import java.util.UUID;

public class AffiliationAlreadyPendingException extends RuntimeException {

    public AffiliationAlreadyPendingException(UUID professionalId, UUID establishmentId) {
        super("An affiliation request is already pending for professional " +
                professionalId + " and establishment " + establishmentId + ".");
    }
}
