package com.ospaulos.vupth.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Uma linha da caixa de suporte do painel (admin-caixa-de-suporte). */
public record AdminSupportMessageRow(
        @JsonProperty("id") UUID id,
        @JsonProperty("name") String name,
        @JsonProperty("email") String email,
        @JsonProperty("profile") String profile,
        @JsonProperty("category") String category,
        @JsonProperty("subject") String subject,
        @JsonProperty("message") String message,
        @JsonProperty("created_at") OffsetDateTime createdAt
) {
}
