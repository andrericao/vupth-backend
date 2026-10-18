package com.ospaulos.vupth.domain.attendance.exception;

import java.time.OffsetDateTime;

/**
 * "O cliente nao compareceu" fora de hora (falta-do-cliente, DEC-83): so o primeiro da fila, e so depois da espera.
 * Vira {@code 409 NO_SHOW_NOT_FIRST} ou {@code 409 NO_SHOW_TOO_EARLY} (esta com {@code available_at}).
 */
public class NoShowException extends RuntimeException {

    private final String code;
    private final OffsetDateTime availableAt;

    private NoShowException(String code, String message, OffsetDateTime availableAt) {
        super(message);
        this.code = code;
        this.availableAt = availableAt;
    }

    public static NoShowException notFirst() {
        return new NoShowException("NO_SHOW_NOT_FIRST",
                "So o primeiro da fila pode ser marcado como nao compareceu.", null);
    }

    public static NoShowException tooEarly(OffsetDateTime availableAt) {
        return new NoShowException("NO_SHOW_TOO_EARLY",
                "Ainda e cedo para marcar que o cliente nao compareceu.", availableAt);
    }

    public String getCode() {
        return code;
    }

    public OffsetDateTime getAvailableAt() {
        return availableAt;
    }
}
