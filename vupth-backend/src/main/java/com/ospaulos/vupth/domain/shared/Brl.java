package com.ospaulos.vupth.domain.shared;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * valor-em-reais-nas-mensagens (22/10): o unico jeito de escrever dinheiro em mensagem, aviso e push — "R$ 53,90" e
 * "R$ 1.234,56" (virgula nos centavos, ponto no milhar). Nunca "R$ 53.90".
 */
public final class Brl {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private Brl() {
    }

    public static String of(BigDecimal amount) {
        return String.format(PT_BR, "R$ %,.2f", amount == null ? BigDecimal.ZERO : amount);
    }
}
