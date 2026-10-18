package com.ospaulos.vupth.api.support.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Mensagem do formulario do site de suporte (formulario-do-suporte, DEC-59). Os nomes dos campos sao
 * os do contrato com o Suporte (vupth-support.pages.dev), em portugues. A {@code categoria} (15/10) serve para o
 * Andre filtrar no e-mail; o assunto segue texto livre. O {@code perfil} e o "Qual o seu perfil" do formulario
 * (categoria-no-formulario, 15/10): cliente, profissional ou dono; o {@code parceiro} antigo fica aceito ate o site trocar.
 */
public record SupportContactRequest(
        @NotBlank @Size(max = 100) String nome,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Pattern(regexp = "cliente|profissional|dono|parceiro",
                message = "perfil deve ser cliente, profissional ou dono") String perfil,
        @NotBlank @Pattern(regexp = "DUVIDA|SUGESTAO|RECLAMACAO|PROBLEMA|PAGAMENTO|ELOGIO|FEEDBACK|OUTRO",
                message = "categoria deve ser DUVIDA, SUGESTAO, RECLAMACAO, PROBLEMA, PAGAMENTO, ELOGIO, FEEDBACK ou OUTRO")
        String categoria,
        @NotBlank @Size(max = 120) String assunto,
        @NotBlank @Size(max = 2000) String mensagem
) {
}
