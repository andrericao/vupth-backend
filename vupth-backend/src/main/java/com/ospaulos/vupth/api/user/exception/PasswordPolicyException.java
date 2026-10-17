package com.ospaulos.vupth.api.user.exception;

/**
 * A senha nova nao cabe na regra (senha-limite-claro): de 6 a 64 caracteres e ate 72 bytes, o teto do BCrypt
 * (acento conta 2 bytes). Vira {@code 422 PASSWORD_TOO_SHORT} ou {@code 422 PASSWORD_TOO_LONG} no
 * {@code GlobalExceptionHandler}, com a mensagem que os apps mostram.
 */
public class PasswordPolicyException extends RuntimeException {

    private final String code;

    private PasswordPolicyException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static PasswordPolicyException tooShort() {
        return new PasswordPolicyException("PASSWORD_TOO_SHORT", "A senha precisa ter de 6 a 64 caracteres.");
    }

    public static PasswordPolicyException tooLong() {
        return new PasswordPolicyException("PASSWORD_TOO_LONG",
                "A senha precisa ter de 6 a 64 caracteres (acentos contam dois).");
    }

    public String getCode() {
        return code;
    }
}
