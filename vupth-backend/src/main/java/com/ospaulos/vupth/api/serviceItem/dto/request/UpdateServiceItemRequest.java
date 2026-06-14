package com.ospaulos.vupth.api.serviceItem.dto.request;

import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Atualizacao parcial de um ServiceItem do profissional (PATCH).
 *
 * Todos os campos opcionais — envie apenas o que deseja alterar:
 *   - customName       → nome exibido do servico
 *   - price            → preco (deve ser > 0 quando enviado)
 *   - estimatedMinutes → duracao estimada (deve ser > 0 quando enviado)
 *
 * Campos null sao ignorados; customName em branco e ignorado.
 */
public record UpdateServiceItemRequest(

        String customName,

        @Positive(message = "Price must be greater than zero")
        BigDecimal price,

        @Positive(message = "Estimated minutes must be greater than zero")
        Integer estimatedMinutes

) {}
