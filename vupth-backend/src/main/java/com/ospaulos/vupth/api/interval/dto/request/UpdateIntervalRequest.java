package com.ospaulos.vupth.api.interval.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;

import java.time.OffsetTime;

/**
 * Edicao de intervalo (spec item #11b/c). Semantica PATCH: so os campos
 * non-null sao aplicados. Usado para alterar horario previsto e duracao
 * das refeicoes recorrentes (almoco/janta) — tambem cobre o ajuste diario
 * da previsao de inicio.
 */
public record UpdateIntervalRequest(
        @Positive @Max(value = 240, message = "durationMinutes must be at most 240") Integer durationMinutes,
        OffsetTime fixedStartTime,
        String description) {
}
