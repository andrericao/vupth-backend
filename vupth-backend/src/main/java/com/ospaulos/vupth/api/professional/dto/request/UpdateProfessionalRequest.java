package com.ospaulos.vupth.api.professional.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Atualizacao parcial do proprio perfil profissional (PATCH /professional/me).
 *
 * Todos os campos sao opcionais — envie apenas o que deseja alterar:
 *   - name          → atualiza o firstName do usuario (nome exibido do profissional)
 *   - specialty     → atualiza a especialidade (String livre)
 *   - contact_phone → telefone/WhatsApp que o cliente ve na tela do profissional; "" deixa de expor
 *   - show_position → interruptor "expor a posicao na fila" (decisao de 05/09)
 *
 * Campos null sao ignorados (nao sobrescrevem o valor atual).
 */
public record UpdateProfessionalRequest(

        @Size(max = 100, message = "Name must be at most 100 characters")
        String name,

        @Size(max = 100, message = "Specialty must be at most 100 characters")
        String specialty,

        @JsonProperty("contact_phone")
        @Size(max = 20, message = "Contact phone must be at most 20 characters")
        @Pattern(regexp = "^$|^\\+?[0-9 ()-]{8,20}$", message = "Contact phone must contain only digits, spaces, (, ), - and an optional leading +")
        String contactPhone,

        @JsonProperty("show_position")
        Boolean showPosition,

        // cpf-do-profissional-opcional (DEC-164): opcional; "" apaga; nunca vai a log
        @Size(max = 20, message = "CPF must be at most 20 characters")
        String cpf
) {
    public UpdateProfessionalRequest(String name, String specialty) {
        this(name, specialty, null, null, null);
    }

    public UpdateProfessionalRequest(String name, String specialty, String contactPhone, Boolean showPosition) {
        this(name, specialty, contactPhone, showPosition, null);
    }

    /** Sem o CPF no toString: o record imprime todos os campos, e o CPF nao pode ir a log. */
    @Override
    public String toString() {
        return "UpdateProfessionalRequest[name=" + name + ", specialty=" + specialty + "]";
    }
}
