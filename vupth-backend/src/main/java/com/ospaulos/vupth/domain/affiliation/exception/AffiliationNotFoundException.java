package com.ospaulos.vupth.domain.affiliation.exception;

import java.util.UUID;

public class AffiliationNotFoundException extends RuntimeException {

    public AffiliationNotFoundException(UUID id) {
        super("Affiliation request not found: " + id);
    }

    /** Convite por e-mail (convite-sem-conta) que nao existe — mesmo 404 AFFILIATION_NOT_FOUND. */
    public static AffiliationNotFoundException emailInvitation(UUID id) {
        return new AffiliationNotFoundException("Email invitation not found: " + id);
    }

    private AffiliationNotFoundException(String message) {
        super(message);
    }
}
