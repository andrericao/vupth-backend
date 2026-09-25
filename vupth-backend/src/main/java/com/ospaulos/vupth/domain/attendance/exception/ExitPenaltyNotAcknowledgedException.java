package com.ospaulos.vupth.domain.attendance.exception;

import lombok.Getter;

import java.math.BigDecimal;

/**
 * Ciencia obrigatoria da multa (Andre, 18/09 e 25/09): "nao podemos agir na surdina". A saida da fila
 * que gera multa so acontece com o "Sim" do cliente — o {@code acknowledgedPenalty} igual ou maior que o
 * valor de agora. Sem ele a saida NAO acontece e a resposta devolve o valor, para o app mostrar o aviso.
 *
 * Mapeada para HTTP 409 com code EXIT_PENALTY_NOT_ACKNOWLEDGED e o campo {@code exitPenaltyValue}.
 */
@Getter
public class ExitPenaltyNotAcknowledgedException extends RuntimeException {

    private final BigDecimal exitPenaltyValue;

    public ExitPenaltyNotAcknowledgedException(BigDecimal exitPenaltyValue, BigDecimal acknowledged) {
        super(acknowledged == null
                ? "Sair agora gera uma multa de R$ " + exitPenaltyValue + " no proximo atendimento. Confirme para sair."
                : "A multa de sair agora e R$ " + exitPenaltyValue + ", maior que a confirmada (R$ " + acknowledged + "). Confirme de novo.");
        this.exitPenaltyValue = exitPenaltyValue;
    }
}
