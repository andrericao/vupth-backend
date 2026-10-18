package com.ospaulos.vupth.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Uma conta na lista de usuarios do painel. Sem telefone, chave Pix nem senha. */
public record AdminUserRow(
        @JsonProperty("id") UUID id,
        @JsonProperty("name") String name,
        @JsonProperty("email") String email,
        @JsonProperty("roles") List<String> roles,
        @JsonProperty("active") boolean active,
        @JsonProperty("deleted") boolean deleted,
        @JsonProperty("created_at") OffsetDateTime createdAt
) {
}
