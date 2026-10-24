package com.ospaulos.vupth.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Uma linha da lista de multas do painel (admin-multas). {@code type}: LATE_ARRIVAL (atraso), NO_SHOW (falta), LATE_CANCEL (saiu
 * da fila ou cancelou em cima da hora) ou PAYMENT_PENDING (corte concluido com o pagamento ainda pendente). {@code state}:
 * GENERATED (gerada, ainda nao cobrada ou nao paga) ou CHARGED (cobrada: o corte que a carregou terminou, ou o Pix avulso foi pago).
 * {@code test}: o atendimento de origem e de um teste (DEC-106).
 */
public record AdminFineRow(
        @JsonProperty("id") UUID id,
        @JsonProperty("type") String type,
        @JsonProperty("client_id") UUID clientId,
        @JsonProperty("client_name") String clientName,
        @JsonProperty("establishment_id") UUID establishmentId,
        @JsonProperty("establishment_name") String establishmentName,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("state") String state,
        @JsonProperty("attendance_id") UUID attendanceId,
        @JsonProperty("test") boolean test,
        @JsonProperty("created_at") OffsetDateTime createdAt
) {
}
