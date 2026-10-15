package com.ospaulos.vupth.api.support.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Mensagem do formulario do site de suporte (formulario-do-suporte, DEC-59). Os nomes dos campos sao
 * os do contrato com o Suporte (vupth-support.pages.dev), em portugues.
 */
public record SupportContactRequest(
        @NotBlank @Size(max = 100) String nome,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Pattern(regexp = "cliente|parceiro", message = "perfil deve ser cliente ou parceiro") String perfil,
        @NotBlank @Size(max = 120) String assunto,
        @NotBlank @Size(max = 2000) String mensagem
) {
}
