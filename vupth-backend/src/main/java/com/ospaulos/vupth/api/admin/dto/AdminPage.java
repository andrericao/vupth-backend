package com.ospaulos.vupth.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** A pagina de uma lista do painel: os itens, onde estamos e quantos ha no total. */
public record AdminPage<T>(
        @JsonProperty("items") List<T> items,
        @JsonProperty("page") int page,
        @JsonProperty("size") int size,
        @JsonProperty("total") long total
) {
}
