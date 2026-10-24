package com.ospaulos.vupth.domain.pricepolicy.exception;

import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * politica-de-preco-com-ciencia (DEC-100). {@code PRICE_POLICY_ACK_REQUIRED} (409): o prazo de 7 dias para registrar a ciencia da
 * nova politica de precos passou; abrir a loja e a fila fica barrado ate registrar. {@code PRICE_POLICY_NOT_FOUND} (404) e
 * {@code PRICE_POLICY_NOTHING_TO_ACK} (409) cobrem a ciencia de uma versao que nao existe ou que nao e dele.
 */
@Getter
public class PricePolicyException extends RuntimeException {

    private final String code;
    private final int status;
    private final Integer policyVersion;
    private final OffsetDateTime deadlineAt;

    private PricePolicyException(String code, int status, String message, Integer policyVersion, OffsetDateTime deadlineAt) {
        super(message);
        this.code = code;
        this.status = status;
        this.policyVersion = policyVersion;
        this.deadlineAt = deadlineAt;
    }

    public static PricePolicyException ackRequired(int version, OffsetDateTime deadline) {
        return new PricePolicyException("PRICE_POLICY_ACK_REQUIRED", 409,
                "Leia a nova política de preços e registre a ciência para abrir a loja e a fila. O prazo de 7 dias terminou.",
                version, deadline);
    }

    public static PricePolicyException notFound() {
        return new PricePolicyException("PRICE_POLICY_NOT_FOUND", 404, "Política de preços não encontrada.", null, null);
    }

    public static PricePolicyException nothingToAck() {
        return new PricePolicyException("PRICE_POLICY_NOTHING_TO_ACK", 409,
                "Não há ciência pendente desta versão da política de preços para você.", null, null);
    }
}
