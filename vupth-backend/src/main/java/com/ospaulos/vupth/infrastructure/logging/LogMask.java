package com.ospaulos.vupth.infrastructure.logging;

/**
 * Mascara dado pessoal antes de ir para o log (S11, DEC-81): o log nao leva e-mail inteiro. O token de aparelho ja tem o {@code PushTokenMask}. Basta a inicial e o dominio para achar o caso.
 */
public final class LogMask {

    private LogMask() {
    }

    /** {@code ana.silva@gmail.com} vira {@code a***@gmail.com}; sem arroba, {@code ***}. */
    public static String email(String email) {
        if (email == null || email.isBlank()) {
            return "-";
        }
        int at = email.indexOf('@');
        if (at < 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
