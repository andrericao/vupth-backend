package com.ospaulos.vupth.domain.serviceItem.exception;

/**
 * O preco do cliente e o valor que o profissional recebe nao cabem na politica de preco
 * (ver {@code PriceSplit}): taxa abaixo de 6%, preco fora do X,90 ou valor que nao e dinheiro.
 */
public class InvalidPriceSplitException extends RuntimeException {
    public InvalidPriceSplitException(String message) {
        super(message);
    }
}
