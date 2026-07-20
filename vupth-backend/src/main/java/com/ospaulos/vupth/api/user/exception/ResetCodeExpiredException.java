package com.ospaulos.vupth.api.user.exception;

/**
 * Código de reset de senha existe e confere, mas já passou da validade (15min).
 * Vira {@code 400 RESET_CODE_EXPIRED} no {@code GlobalExceptionHandler} — o front pede um novo.
 */
public class ResetCodeExpiredException extends RuntimeException {
    public ResetCodeExpiredException() {
        super("Password reset code expired");
    }
}
