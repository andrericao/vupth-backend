package com.ospaulos.vupth.api.client.dto.response;

import java.util.UUID;

/**
 * Response DTO for GET /clients/{id}
 *
 * Exposes public-facing client profile data.
 * MVP: id, name, email. Additional fields (photo, stats) added in future sprints.
 *
 * <p>{@code strikeCount}: SAIU (Andre, 26/09) — vem sempre 0 ate os apps tirarem o campo; depois sai.
 * A regua do cliente agora e {@code lateCount} + {@code latesUntilPenalty} (regra dos 3 atrasos).</p>
 */
public record ClientResponse(
        UUID id,
        String firstName,
        String email,
        int strikeCount,
        // D4: atrasos acumulados e quantos faltam para a multa ("este e o seu 2o atraso").
        int lateCount,
        int latesUntilPenalty
) {}
