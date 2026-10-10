package com.ospaulos.vupth.domain.payment.exception;

import java.util.UUID;

/** 409 PAYMENT_NOT_AVAILABLE: atendimento cancelado, retirado ou anterior ao pagamento (pagamento-pix). */
public class PaymentNotAvailableException extends RuntimeException {
    public PaymentNotAvailableException(UUID attendanceId) {
        super("Este atendimento nao tem pagamento: foi cancelado, retirado ou e anterior ao pagamento pelo app. attendance="
                + attendanceId);
    }
}
