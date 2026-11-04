package com.ospaulos.vupth.domain.support.exception;

/** O formulario do suporte passou do teto do dia (por IP ou geral) — vira 429 SUPPORT_DAILY_LIMIT. */
public class SupportDailyLimitException extends RuntimeException {
    public SupportDailyLimitException() {
        super("Limite diario de mensagens do suporte atingido, tente amanha");
    }
}
