package com.ospaulos.vupth.api.attendance.dto.request;

import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

/**
 * Edicao de uma intencao de atendimento (backlog #4 / status INTENTION). O cliente dono (por JWT)
 * altera o horario PRETENDIDO ({@code desiredTime}). Passa pelo MESMO algoritmo do create (valida
 * futuro/expediente/horizonte/invariante e recalcula a estimativa). Enquanto for INTENTION; um
 * atendimento ja promovido ({@code SCHEDULED}) ou cancelado nao pode ser editado por aqui.
 */
public record UpdateIntentionRequest(
        // must be ISO-8601 string — enforced by JacksonConfig.StrictOffsetDateTimeDeserializer
        @NotNull OffsetDateTime desiredTime
) {
}
