package com.ospaulos.vupth.domain.establishment.exception;

import java.util.UUID;

public class EstablishmentAccessDeniedException extends RuntimeException {

    public EstablishmentAccessDeniedException(UUID professionalId, UUID establishmentId) {
        super(String.format(
            "Professional '%s' does not belong to establishment '%s'",
            professionalId, establishmentId));
    }

    public EstablishmentAccessDeniedException(String message) {
        super(message);
    }
}
