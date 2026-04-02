package com.ospaulos.vupth.api.client.dto.response;

import java.util.UUID;

/**
 * Response DTO for GET /clients/{id}
 *
 * Exposes public-facing client profile data.
 * MVP: id, name, email. Additional fields (photo, stats) added in future sprints.
 */
public record ClientResponse(
        UUID id,
        String firstName,
        String email
) {}
