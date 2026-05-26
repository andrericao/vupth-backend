package com.ospaulos.vupth.domain.professional.exception;

import java.util.UUID;

public class ProfessionalNotAffiliatedException extends RuntimeException {

    public ProfessionalNotAffiliatedException(UUID professionalId) {
        super("Professional " + professionalId + " is not affiliated to any establishment.");
    }
}
