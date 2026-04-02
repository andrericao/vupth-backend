package com.ospaulos.vupth.domain.professional.exception;

import java.util.UUID;

public class ProfessionalNotFoundException extends RuntimeException {
    public ProfessionalNotFoundException(UUID id) {
        super("Professional not found with id: " + id);
    }
}