package com.ospaulos.vupth.domain.professional.exception;

import java.util.UUID;

/**
 * Lançada quando um profissional tenta se afiliar a um establishment
 * mas já possui um vínculo ativo.
 *
 * Regra MVP: um profissional = um establishment.
 * HTTP: 409 CONFLICT — code: PROFESSIONAL_ALREADY_AFFILIATED
 */
public class ProfessionalAlreadyAffiliatedException extends RuntimeException {

    public ProfessionalAlreadyAffiliatedException(UUID professionalId, UUID currentEstablishmentId) {
        super(String.format(
                "Professional %s is already affiliated to establishment %s. " +
                "A professional can only be linked to one establishment in the MVP.",
                professionalId, currentEstablishmentId
        ));
    }
}
