package com.ospaulos.vupth.domain.payment.exception;

import com.ospaulos.vupth.api.payment.dto.response.PendingPaymentsResponse;

/**
 * 409 PAYMENT_PENDING: o cliente tem atendimento finalizado sem pagar e tentou entrar numa fila,
 * agendar ou pedir corte. Leva a pendencia para o app mostrar (pagamento-pix).
 */
public class PaymentPendingException extends RuntimeException {

    private final transient PendingPaymentsResponse pending;

    public PaymentPendingException(PendingPaymentsResponse pending) {
        super("Voce tem pagamento pendente. Pague para entrar numa nova fila.");
        this.pending = pending;
    }

    public PendingPaymentsResponse getPending() {
        return pending;
    }
}
