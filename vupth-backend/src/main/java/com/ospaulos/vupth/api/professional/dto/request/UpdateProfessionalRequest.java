package com.ospaulos.vupth.api.professional.dto.request;

import jakarta.validation.constraints.Size;

/**
 * Atualizacao parcial do proprio perfil profissional (PATCH /professional/me).
 *
 * Ambos os campos sao opcionais — envie apenas o que deseja alterar:
 *   - name      → atualiza o firstName do usuario (nome exibido do profissional)
 *   - specialty → atualiza a especialidade (String livre)
 *
 * Campos null/em branco sao ignorados (nao sobrescrevem o valor atual).
 */
public record UpdateProfessionalRequest(

        @Size(max = 100, message = "Name must be at most 100 characters")
        String name,

        @Size(max = 100, message = "Specialty must be at most 100 characters")
        String specialty

) {}
