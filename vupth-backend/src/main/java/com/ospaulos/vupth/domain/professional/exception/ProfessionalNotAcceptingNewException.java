package com.ospaulos.vupth.domain.professional.exception;

import java.util.UUID;

/**
 * S1 fatia 3 — o profissional fechou a fila ({@code accepting_new = false}): cliente novo nao entra
 * na fila viva (walk-in imediato). Mapeada para 409 {@code PROFESSIONAL_NOT_ACCEPTING_NEW}.
 * O cliente ainda pode agendar para uma data futura (intencao #40).
 */
public class ProfessionalNotAcceptingNewException extends RuntimeException {

    public ProfessionalNotAcceptingNewException(UUID professionalId) {
        super("Professional " + professionalId + " is not accepting new clients (queue closed).");
    }
}
