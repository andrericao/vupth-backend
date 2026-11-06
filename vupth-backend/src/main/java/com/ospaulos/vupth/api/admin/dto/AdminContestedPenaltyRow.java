package com.ospaulos.vupth.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Uma multa contestada pelo cliente, a decidir no Painel (multa-contestavel, F5): quem acusou, quando e o motivo. */
public record AdminContestedPenaltyRow(
        @JsonProperty("id") UUID id,
        @JsonProperty("type") String type,
        @JsonProperty("client_id") UUID clientId,
        @JsonProperty("client_name") String clientName,
        @JsonProperty("establishment_id") UUID establishmentId,
        @JsonProperty("establishment_name") String establishmentName,
        @JsonProperty("accused_by") String accusedBy,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("contested_at") OffsetDateTime contestedAt,
        @JsonProperty("contest_reason") String contestReason,
        @JsonProperty("test") boolean test
) {
}
