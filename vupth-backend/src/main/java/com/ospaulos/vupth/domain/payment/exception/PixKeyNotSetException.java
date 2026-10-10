package com.ospaulos.vupth.domain.payment.exception;

/** 404 PIX_KEY_NOT_SET: o profissional ainda nao cadastrou a chave Pix (pagamento-pix). */
public class PixKeyNotSetException extends RuntimeException {
    public PixKeyNotSetException() {
        super("Chave Pix ainda nao cadastrada.");
    }
}
