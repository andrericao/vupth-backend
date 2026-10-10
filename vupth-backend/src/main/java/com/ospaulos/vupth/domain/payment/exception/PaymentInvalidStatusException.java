package com.ospaulos.vupth.domain.payment.exception;

import com.ospaulos.vupth.domain.payment.PaymentStatus;

/** 409 PAYMENT_INVALID_STATUS: passo fora de ordem (ex.: "Ja paguei" depois do "Recebi"). */
public class PaymentInvalidStatusException extends RuntimeException {
    public PaymentInvalidStatusException(PaymentStatus current, String action) {
        super("Nao da para " + action + " com o pagamento em " + current + ".");
    }
}
