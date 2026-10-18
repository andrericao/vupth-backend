package com.ospaulos.vupth.domain.payment;

/**
 * Como o profissional diz que recebeu (DEC-85, fase de testes sem gateway): Pix, maquina de cartao, dinheiro ou nota. E so
 * o registro, para a conciliacao futura; qualquer uma quita o pagamento. Opcional.
 */
public enum PaymentMethod {
    PIX,
    CARTAO,
    DINHEIRO,
    NOTA
}
