package com.ospaulos.vupth.api.client.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Multas que o cliente ainda deve (D4). O app soma {@code total} aos servicos marcados na hora de entrar
 * na fila, separando "cobrancas do servico" de "cobrancas do app", e cita cada multa pela origem.
 *
 * <p>multas-carregam-na-virada (DEC-175): na virada do gateway as multas pendentes carregam (sem anistia). {@code testTotal}
 * e a parte nascida no periodo de teste (vai ao profissional) e {@code appTotal} a cobrada pelo app; {@code total} e a soma.
 */
public record PendingPenaltiesResponse(
        BigDecimal total,
        List<PenaltyChargeResponse> penalties,
        BigDecimal testTotal,
        BigDecimal appTotal
) {
    public static PendingPenaltiesResponse of(List<PenaltyChargeResponse> penalties) {
        BigDecimal test = sum(penalties, true);
        BigDecimal app = sum(penalties, false);
        return new PendingPenaltiesResponse(test.add(app), penalties, test, app);
    }

    private static BigDecimal sum(List<PenaltyChargeResponse> penalties, boolean testPeriod) {
        return penalties.stream().filter(p -> p.testPeriod() == testPeriod)
                .map(PenaltyChargeResponse::amount)
                .reduce(new BigDecimal("0.00"), BigDecimal::add);
    }
}
