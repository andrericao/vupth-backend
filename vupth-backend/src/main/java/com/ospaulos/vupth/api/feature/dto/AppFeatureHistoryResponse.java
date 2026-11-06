package com.ospaulos.vupth.api.feature.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Uma mudanca da chave; {@code previous} e {@code current} sao a regra antes e depois. */
public record AppFeatureHistoryResponse(
        @JsonProperty("changed_by") UUID changedBy,
        @JsonProperty("changed_at") OffsetDateTime changedAt,
        @JsonProperty("reason") String reason,
        @JsonProperty("previous") @JsonRawValue String previous,
        @JsonProperty("current") @JsonRawValue String current) {
}
