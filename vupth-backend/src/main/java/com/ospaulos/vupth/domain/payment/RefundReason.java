package com.ospaulos.vupth.domain.payment;

/**
 * Por que o atendimento estornado nao houve (estorno-asaas-casos, DEC-137 caso 4). Fica gravado para o Andre decidir depois se o
 * cupom volta; por ora o cupom nao volta.
 */
public enum RefundReason {
    /** O cliente desistiu ou cancelou. */
    CLIENT_CANCELLED,
    /** O profissional nao atendeu (faltou, fechou, recusou). */
    PROFESSIONAL_NO_SHOW,
    /** O atendimento nao foi finalizado. */
    NOT_FINISHED,
    /** O cliente pagou duas vezes (Pix do Asaas e "Recebi" manual). */
    DOUBLE_PAYMENT,
    /** Contestado, a criterio do ADMIN. */
    DISPUTED
}
