package com.ospaulos.vupth.domain.payment.exception;

/** 422 PIX_KEY_INVALID: a chave nao bate com o tipo (pagamento-pix). */
public class PixKeyInvalidException extends RuntimeException {
    public PixKeyInvalidException(String message) {
        super(message);
    }
}
