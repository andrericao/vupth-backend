package com.ospaulos.vupth.api.serviceItem.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Atualizacao parcial de um ServiceItem do profissional (PATCH).
 *
 * Todos os campos opcionais — envie apenas o que deseja alterar:
 *   - customName       → nome exibido do servico
 *   - price            → preco (deve ser > 0 quando enviado)
 *   - estimatedMinutes → duracao estimada (deve ser > 0 quando enviado)
 *   - pro_receives + base_price → a divisao do preco (os dois juntos); passa pela regra de preco
 *                        junto com o price (ou com o preco atual, se price nao vier). Price novo SEM
 *                        a divisao apaga a divisao antiga (ver ServiceItem#reprice).
 *
 * Campos null sao ignorados; customName em branco e ignorado.
 */
public record UpdateServiceItemRequest(

        String customName,

        @Positive(message = "Price must be greater than zero")
        BigDecimal price,

        @Positive(message = "Estimated minutes must be greater than zero")
        Integer estimatedMinutes,

        @JsonProperty("client_price")
        @Positive(message = "Client price must be greater than zero")
        BigDecimal clientPrice,

        @JsonProperty("uses_platform_pricing")
        Boolean usesPlatformPricing,

        @JsonProperty("pro_receives")
        BigDecimal proReceives,

        @JsonProperty("base_price")
        BigDecimal basePrice

) {
    /** Sem {@code pro_receives}. */
    public UpdateServiceItemRequest(String customName, BigDecimal price, Integer estimatedMinutes,
                                    BigDecimal clientPrice, Boolean usesPlatformPricing) {
        this(customName, price, estimatedMinutes, clientPrice, usesPlatformPricing, null, null);
    }
}
