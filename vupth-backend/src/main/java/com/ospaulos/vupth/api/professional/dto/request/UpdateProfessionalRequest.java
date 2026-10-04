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
        String contactPhone

) {
    public UpdateProfessionalRequest(String name, String specialty) {
        this(name, specialty, null);
    }
}
