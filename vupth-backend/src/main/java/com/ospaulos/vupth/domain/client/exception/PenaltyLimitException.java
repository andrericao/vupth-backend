package com.ospaulos.vupth.domain.client.exception;

import com.ospaulos.vupth.api.client.dto.response.PenaltyChargeResponse;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * O cliente chegou ao limite de multas devidas (limite-de-duas-multas, DEC-83): so marca depois de quitar. Vira
 * {@code 409 PENALTY_LIMIT_REACHED} com o limite, o total, as multas e o pagamento avulso vigente (se houver).
 */
public class PenaltyLimitException extends RuntimeException {

    private final int limit;
    private final BigDecimal total;
    private final List<PenaltyChargeResponse> penalties;
    private final UUID paymentId;

    public PenaltyLimitException(int limit, BigDecimal total, List<PenaltyChargeResponse> penalties, UUID paymentId) {
        super("Voce tem " + penalties.size() + " multas a pagar. Pague para marcar um novo corte.");
        this.limit = limit;
        this.total = total;
        this.penalties = penalties;
        this.paymentId = paymentId;
    }

    public int getLimit() {
        return limit;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public List<PenaltyChargeResponse> getPenalties() {
        return penalties;
    }

    public UUID getPaymentId() {
        return paymentId;
    }
}
