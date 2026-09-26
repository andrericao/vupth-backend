package com.ospaulos.vupth.api.client.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Multas que o cliente ainda deve (D4). O app soma {@code total} aos servicos marcados na hora de entrar
 * na fila, separando "cobrancas do servico" de "cobrancas do app", e cita cada multa pela origem.
 */
public record PendingPenaltiesResponse(
        BigDecimal total,
        List<PenaltyChargeResponse> penalties
) {}
