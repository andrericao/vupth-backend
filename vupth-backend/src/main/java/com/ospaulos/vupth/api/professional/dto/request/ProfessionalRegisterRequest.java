package com.ospaulos.vupth.api.professional.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Request para cadastro de profissional sem establishment.
 *
 * Usado pelo app parceiro quando o usuário escolhe "sou profissional"
 * sem criar ou pertencer a um establishment ainda.
 *
 * O profissional ficará com establishment = null até que:
 *   a) Um owner o vincule via POST /establishment/{id}/professionals
 *   b) Ele próprio use POST /professional/affiliate (se já tiver o establishmentId)
 */
public record ProfessionalRegisterRequest(

        @NotBlank(message = "Professional name is required")
        String name,

        String specialty

) {}
