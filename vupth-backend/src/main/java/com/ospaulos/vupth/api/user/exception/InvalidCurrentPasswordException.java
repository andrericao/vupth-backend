package com.ospaulos.vupth.api.user.exception;

/**
 * A senha atual informada na troca de senha nao confere com a do usuario autenticado.
 * Vira {@code 401 CURRENT_PASSWORD_INVALID} no {@code GlobalExceptionHandler}.
 */
public class InvalidCurrentPasswordException extends RuntimeException {
    public InvalidCurrentPasswordException() {
        super("Current password does not match");
    }
}
