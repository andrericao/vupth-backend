package com.ospaulos.vupth.api.establishment.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request para o owner adicionar um profissional ao seu establishment.
 *
 * O owner informa o ID do profissional que deseja vincular.
 * O profissional deve existir e não estar vinculado a outro establishment.
 *
 * HTTP: POST /establishment/{establishmentId}/professionals
 * Auth: ROLE_OWNER
 */
public record AddProfessionalToEstablishmentRequest(

        @NotNull(message = "Professional ID is required")
        UUID professionalId

) {}
