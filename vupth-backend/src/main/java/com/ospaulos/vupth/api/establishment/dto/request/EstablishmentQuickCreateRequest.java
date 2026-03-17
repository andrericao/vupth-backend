package com.ospaulos.vupth.api.establishment.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Cadastro simplificado (MVP):
 * - Only the name is required.
 * - A fixed UUID is generated on creation and never changes.
 * - The owner can complete remaining info later (serviceTypes, photo, address).
 */
public record EstablishmentQuickCreateRequest(
        @NotBlank(message = "Establishment name is required") String name
) {}
