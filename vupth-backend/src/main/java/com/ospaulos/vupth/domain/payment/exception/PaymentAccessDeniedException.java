package com.ospaulos.vupth.domain.payment.exception;

/** 403 PAYMENT_ACCESS_DENIED: quem chamou nao e a parte certa deste pagamento. */
public class PaymentAccessDeniedException extends RuntimeException {
    public PaymentAccessDeniedException(String message) {
        super(message);
    }
}
