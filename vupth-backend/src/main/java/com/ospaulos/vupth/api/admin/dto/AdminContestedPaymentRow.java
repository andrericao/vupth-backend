package com.ospaulos.vupth.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Uma linha da pendencia "pagamento contestado" do painel (pagamento-declarado-contestavel, F4): o profissional marcou "Recebi" e
 * o cliente disse que nao pagou. {@code marked_by}/{@code marked_device}: quem marcou e de qual aparelho; {@code reason}: o motivo
 * do cliente (opcional).
 */
public record AdminContestedPaymentRow(
        @JsonProperty("attendance_id") UUID attendanceId,
        @JsonProperty("client_id") UUID clientId,
        @JsonProperty("client_name") String clientName,
        @JsonProperty("establishment_id") UUID establishmentId,
        @JsonProperty("establishment_name") String establishmentName,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("marked_by") UUID markedBy,
        @JsonProperty("marked_device") String markedDevice,
        @JsonProperty("marked_at") OffsetDateTime markedAt,
        @JsonProperty("contested_at") OffsetDateTime contestedAt,
        @JsonProperty("reason") String reason,
        @JsonProperty("test") boolean test
) {
}
