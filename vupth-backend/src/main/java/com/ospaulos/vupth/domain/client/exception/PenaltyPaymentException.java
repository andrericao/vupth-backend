package com.ospaulos.vupth.domain.client.exception;

import org.springframework.http.HttpStatus;

/** Falha no pagamento avulso da multa (pagar-multa-avulso): nada a pagar, Pix do app sem chave, pagamento vencido... */
public class PenaltyPaymentException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public PenaltyPaymentException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
