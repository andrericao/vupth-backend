package com.ospaulos.vupth.domain.establishment.exception;

import java.util.UUID;

public class EstablishmentNotFoundException extends RuntimeException {
    public EstablishmentNotFoundException(UUID id) {
        super("Establishment not found with id: " + id);
    }
}
