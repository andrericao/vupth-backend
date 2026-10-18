package com.ospaulos.vupth.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Como o dinheiro do corte e dividido (fase-de-testes-sem-gateway, DEC-85). Sem gateway de pagamento o app nao vai ao
 * publico; na fase de testes vale {@code TEST_PIX_TOTAL} (o padrao): o Pix do corte leva o total, servico mais multas, ao
 * profissional, a multa nao tem Pix proprio, nao se paga avulso e nao ha limite de multas. {@code SPLIT_PIX} e o modo dos
 * dois Pix (o do servico ao profissional, o da multa ao app, pagar-multa-avulso) para a volta do gateway. Variavel
 * {@code PAYMENT_MODE}.
 */
@Component
public class PaymentMode {

    public static final String TEST_PIX_TOTAL = "TEST_PIX_TOTAL";
    public static final String SPLIT_PIX = "SPLIT_PIX";

    private final boolean split;

    public PaymentMode(@Value("${vupth.payment.mode:TEST_PIX_TOTAL}") String mode) {
        this.split = SPLIT_PIX.equalsIgnoreCase(mode == null ? "" : mode.trim());
    }

    /** Os dois Pix: o do servico ao profissional e o da multa ao app. */
    public boolean splitPix() {
        return split;
    }

    public String name() {
        return split ? SPLIT_PIX : TEST_PIX_TOTAL;
    }
}
