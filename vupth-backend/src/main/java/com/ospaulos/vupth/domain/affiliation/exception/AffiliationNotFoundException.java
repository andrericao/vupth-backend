package com.ospaulos.vupth.domain.affiliation.exception;

import java.util.UUID;

public class AffiliationNotFoundException extends RuntimeException {

    public AffiliationNotFoundException(UUID id) {
        super("Affiliation request not found: " + id);
    }
}
