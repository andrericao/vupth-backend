package com.ospaulos.vupth.api.user.policy;

import com.ospaulos.vupth.api.user.exception.PasswordPolicyException;

import java.nio.charset.StandardCharsets;

/**
 * A regra da senha nova, no cadastro, na troca e na redefinicao (senha-limite-claro): de 6 a 64 caracteres (DEC-79,
 * igual nos quatro apps) e, alem disso, no maximo 72 bytes em UTF-8 — o BCrypt so le os primeiros 72 bytes e o
 * Spring recusa uma senha maior; com acento, 64 caracteres podem estourar os bytes.
 */
public final class PasswordPolicy {

    public static final int MIN_CHARS = 6;
    public static final int MAX_CHARS = 64;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    public static void check(String password) {
        if (password == null || password.length() < MIN_CHARS) {
            throw PasswordPolicyException.tooShort();
        }
        if (password.length() > MAX_CHARS || !fitsBcrypt(password)) {
            throw PasswordPolicyException.tooLong();
        }
    }

    /** Cabe no BCrypt (ate 72 bytes em UTF-8). */
    public static boolean fitsBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }
}
