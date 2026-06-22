package com.ospaulos.vupth.api.serviceItem.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Item da busca/resolucao de tipo de servico (GET /service-types[?q=]).
 *
 * Resolve o termo que o cliente conhece (ex.: "luzes") para o tipo canonico
 * (PINTURA), devolvendo o nome oficial, as categorias (serviceTypes) para o front
 * filtrar estabelecimentos, a lista completa de sinonimos (para o "i" de informacao)
 * e qual sinonimo casou com a busca (matchedKeyword — null quando lista sem filtro
 * ou quando casou pelo proprio nome do servico).
 */
public record ServiceTypeSearchResponse(

        String type,

        @JsonProperty("display_name")
        String displayName,

        @JsonProperty("service_types")
        List<String> serviceTypes,

        List<String> keywords,

        @JsonProperty("matched_keyword")
        String matchedKeyword
) {}
