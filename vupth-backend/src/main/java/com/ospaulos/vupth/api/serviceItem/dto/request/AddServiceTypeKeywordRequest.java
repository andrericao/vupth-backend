package com.ospaulos.vupth.api.serviceItem.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Adiciona um sinonimo/palavra-chave a um tipo de servico (item 34).
 * So profissionais. O valor e normalizado (minuscula, sem acento) antes de gravar.
 */
public record AddServiceTypeKeywordRequest(

        @NotBlank(message = "keyword é obrigatória")
        @Size(max = 60, message = "keyword deve ter no máximo 60 caracteres")
        String keyword
) {}
