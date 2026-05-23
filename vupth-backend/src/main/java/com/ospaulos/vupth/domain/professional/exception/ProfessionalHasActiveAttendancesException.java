package com.ospaulos.vupth.domain.professional.exception;

import java.util.UUID;

public class ProfessionalHasActiveAttendancesException extends RuntimeException {

    public ProfessionalHasActiveAttendancesException(UUID professionalId) {
        super("Professional " + professionalId + " has active attendances and cannot be removed.");
    }
}
