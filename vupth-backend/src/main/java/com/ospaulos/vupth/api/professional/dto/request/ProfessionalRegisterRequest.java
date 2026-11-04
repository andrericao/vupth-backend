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

        String specialty,

        // cpf-do-profissional-opcional (DEC-164): opcional no cadastro; nunca vai a log
        @jakarta.validation.constraints.Size(max = 20, message = "CPF must be at most 20 characters")
        String cpf
) {
    public ProfessionalRegisterRequest(String name, String specialty) {
        this(name, specialty, null);
    }

    /** Sem o CPF no toString: o record imprime todos os campos, e o CPF nao pode ir a log. */
    @Override
    public String toString() {
        return "ProfessionalRegisterRequest[name=" + name + ", specialty=" + specialty + "]";
    }
}
