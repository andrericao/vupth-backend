package com.ospaulos.vupth.api.professional.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Cadastro simplificado de profissional (MVP):
 * - Name is required.
 * - Establishment ID is required (to link the professional to the correct location).
 * - Specialty, photo and other fields are optional — can be filled in later.
 * - The generated ID is fixed and permanent.
 */
public record ProfessionalQuickCreateRequest(

        @NotBlank(message = "Professional name is required")
        String name,

        @NotNull(message = "O Establishment ID is required")
        UUID establishmentId,

        String specialty
) {}
