package com.ospaulos.vupth.domain.owner.exception;

import java.util.UUID;

public class OwnerNotFoundException extends RuntimeException {
    public OwnerNotFoundException(UUID id) {
        super("Owner not found with id: " + id);
    }
}
