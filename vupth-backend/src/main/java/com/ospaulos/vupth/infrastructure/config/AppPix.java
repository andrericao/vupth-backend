package com.ospaulos.vupth.infrastructure.config;

import com.ospaulos.vupth.domain.payment.PixBrCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * O Pix do app, que recebe as multas (DEC-83: a multa e do app, o servico e do profissional). A chave vem de
 * {@code APP_PIX_KEY} (ja normalizada: celular +55..., e-mail, CPF/CNPJ so numeros ou chave aleatoria); sem ela, o Pix
 * da multa nao e gerado e o app so mostra o valor.
 */
@Component
public class AppPix {

    private final String key;
    private final String holderName;
    private final String city;

    public AppPix(@Value("${vupth.app-pix.key:}") String key,
                  @Value("${vupth.app-pix.holder-name:VUPTH}") String holderName,
                  @Value("${vupth.app-pix.city:SAO PAULO}") String city) {
        this.key = key == null ? "" : key.trim();
        this.holderName = holderName;
        this.city = city;
    }

    public boolean configured() {
        return !key.isBlank();
    }

    public String holderName() {
        return holderName;
    }

    /** O Pix copia-e-cola do valor, ou nulo sem chave. {@code txid} identifica o pagamento. */
    public String copyPaste(BigDecimal amount, String txid) {
        return configured() ? PixBrCode.build(key, holderName, city, amount, txid) : null;
    }
}
