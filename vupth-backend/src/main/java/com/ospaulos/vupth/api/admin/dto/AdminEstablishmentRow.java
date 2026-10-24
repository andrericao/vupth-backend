package com.ospaulos.vupth.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.UUID;

/** Uma loja na lista de estabelecimentos do painel. */
public record AdminEstablishmentRow(
        @JsonProperty("id") UUID id,
        @JsonProperty("name") String name,
        @JsonProperty("status") String status,
        @JsonProperty("open_status") String openStatus,
        @JsonProperty("owner_id") UUID ownerId,
        @JsonProperty("owner_name") String ownerName,
        @JsonProperty("professionals") long professionals,
        @JsonProperty("created_at") LocalDateTime createdAt,
        // o teste ativo da loja (DEC-106); nulos quando nao ha
        @JsonProperty("active_test_id") UUID activeTestId,
        @JsonProperty("active_test_name") String activeTestName
) {
}
