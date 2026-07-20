package com.ospaulos.vupth.api.user.exception;

/**
 * Código de reset de senha inválido: inexistente, não confere, já usado ou tentativas esgotadas.
 * Vira {@code 400 RESET_CODE_INVALID} no {@code GlobalExceptionHandler}. Mensagem genérica de
 * propósito — não distingue "não existe" de "errado" para não vazar informação.
 */
public class InvalidResetCodeException extends RuntimeException {
    public InvalidResetCodeException() {
        super("Invalid password reset code");
    }
}
