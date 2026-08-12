package com.ospaulos.vupth.infrastructure.push;

/**
 * Mascara o Expo Push Token para log (backlog #28).
 *
 * <p>O token e semi-secreto: quem o tem consegue disparar push para aquele aparelho pela Expo, sem
 * autenticacao nenhuma. Ao mesmo tempo, o log precisa identificar QUAL device falhou para o QA
 * conseguir correlacionar. O meio-termo e a cauda — suficiente para distinguir dois aparelhos do
 * mesmo usuario, inutil para quem so lesse o log.</p>
 */
final class PushTokenMask {

    private static final int VISIBLE_TAIL = 6;

    private PushTokenMask() {
    }

    static String mask(String token) {
        if (token == null || token.isBlank()) {
            return "<vazio>";
        }
        if (token.length() <= VISIBLE_TAIL) {
            return "***";
        }
        return "***" + token.substring(token.length() - VISIBLE_TAIL);
    }
}
