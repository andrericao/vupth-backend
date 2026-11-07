package com.ospaulos.vupth.api.feature.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * artigo-do-recurso-no-suporte: um artigo visivel. {@code release}: {@code ALL} (sem faixa) ou {@code PARTIAL} (faixa "ainda nao foi
 * disponibilizado para todos"). {@code profiles}: para quem o recurso vale; lista vazia = todos. Artigo que nao vem aqui esta oculto.
 */
public record AppFeatureArticleResponse(
        @JsonProperty("key") String key,
        @JsonProperty("article_path") String articlePath,
        @JsonProperty("profiles") List<String> profiles,
        @JsonProperty("release") String release) {
}
