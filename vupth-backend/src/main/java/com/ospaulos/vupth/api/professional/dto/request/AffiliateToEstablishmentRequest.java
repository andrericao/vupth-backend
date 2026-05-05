package com.ospaulos.vupth.api.professional.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request para afiliar um profissional existente a um establishment.
 *
 * Regra MVP: um profissional só pode estar vinculado a um establishment por vez.
 * Se já estiver afiliado → 409 PROFESSIONAL_ALREADY_AFFILIATED.
 */
public record AffiliateToEstablishmentRequest(

        @NotNull(message = "Establishment ID is required")
        UUID establishmentId

) {}
