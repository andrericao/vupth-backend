package com.ospaulos.vupth.domain.user;

import com.ospaulos.vupth.domain.payment.PixKeyType;
import com.ospaulos.vupth.domain.user.exception.CpfInvalidException;

/** CPF: normaliza (so digitos, com a conferencia dos digitos) e mascara. Nunca loga o valor. */
public final class Cpf {

    private Cpf() {}

    /** Os 11 digitos do CPF; {@link CpfInvalidException} se nao fecharem. */
    public static String normalize(String raw) {
        return PixKeyType.CPF.normalize(raw).orElseThrow(CpfInvalidException::new);
    }

    /** {@code 12345678909} vira {@code ***.456.789-**}; null/vazio, null. */
    public static String mask(String digits) {
        if (digits == null || digits.length() != 11) {
            return null;
        }
        return "***." + digits.substring(3, 6) + "." + digits.substring(6, 9) + "-**";
    }
}
