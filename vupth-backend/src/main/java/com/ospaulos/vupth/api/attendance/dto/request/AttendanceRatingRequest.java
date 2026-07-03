package com.ospaulos.vupth.api.attendance.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Avaliacao de um atendimento concluido (item #6).
 *
 * <ul>
 *   <li>{@code score} — obrigatorio, inteiro de 1 a 5.</li>
 *   <li>{@code comment} — opcional, ate 500 caracteres.</li>
 * </ul>
 */
public record AttendanceRatingRequest(

        @NotNull(message = "Score is required")
        @Min(value = 1, message = "Score must be at least 1")
        @Max(value = 5, message = "Score must be at most 5")
        Integer score,

        @Size(max = 500, message = "Comment must be at most 500 characters")
        String comment
) {
}
