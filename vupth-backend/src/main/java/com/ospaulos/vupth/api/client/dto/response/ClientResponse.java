package com.ospaulos.vupth.api.client.dto.response;

import java.util.UUID;

/**
 * Response DTO for GET /clients/{id}
 *
 * Exposes public-facing client profile data.
 * MVP: id, name, email. Additional fields (photo, stats) added in future sprints.
 *
 * <p>{@code strikeCount} (item #39, Fase 1): total de penalidades acumuladas pelo cliente
 * (ex.: cancelamentos tardios com ETA &lt; 1h). O front usa para exibir o aviso da politica de
 * cancelamento. Fase 1 apenas expoe a contagem — sem bloqueio automatico.</p>
 */
public record ClientResponse(
        UUID id,
        String firstName,
        String email,
        int strikeCount
) {}
