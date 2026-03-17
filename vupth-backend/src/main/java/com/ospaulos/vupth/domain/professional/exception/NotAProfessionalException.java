package com.ospaulos.vupth.domain.professional.exception;

import java.util.UUID;

public class NotAProfessionalException extends RuntimeException {
    public NotAProfessionalException(UUID id) {
        super("User with " + id + " is not a Professional!");
    }
}
