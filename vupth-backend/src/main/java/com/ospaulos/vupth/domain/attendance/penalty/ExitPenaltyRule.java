package com.ospaulos.vupth.domain.attendance.penalty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Regra da multa por SAIR da fila (D4 — Andre 17/09, tolerancia de 24/09, sem isencao em 25/09).
 *
 * <ul>
 *   <li><b>Tolerancia</b>, contada da entrada e decidida pela espera prevista NAQUELE momento:
 *       espera de 30 min ou menos sai de graca por 5 min; acima disso, por 10 min.</li>
 *   <li><b>Valor</b>, pelo tempo que falta para a vez NA HORA DA SAIDA: 30 min ou menos → R$ 5;
 *       entre 30 min e 1h → R$ 3; 1h ou mais → sem multa.</li>
 *   <li><b>Sem isencao</b> para quem esta sozinho: a saida atinge o profissional mesmo sem fila atras
 *       ("pode ter perdido um walk-in ou projetado o almoco").</li>
 * </ul>
 *
 * <p>Funcao pura: quem chama decide o "tempo que falta" — o mesmo {@code estimatedWaitingTime} que o
 * cliente le, porque a regua da multa e o horario COMUNICADO.
 */
public final class ExitPenaltyRule {

    static final int SHORT_WAIT_MINUTES = 30;
    static final int NO_PENALTY_FROM_MINUTES = 60;
    static final int SHORT_WAIT_TOLERANCE_MINUTES = 5;
    static final int LONG_WAIT_TOLERANCE_MINUTES = 10;

    static final BigDecimal SHORT_WAIT_PENALTY = new BigDecimal("5.00");
    static final BigDecimal MEDIUM_WAIT_PENALTY = new BigDecimal("3.00");
    static final BigDecimal NO_PENALTY = new BigDecimal("0.00");

    private ExitPenaltyRule() {
    }

    /** Minutos de saida gratuita, pela espera prevista na entrada. */
    public static int toleranceMinutes(int waitAtEntryMinutes) {
        return waitAtEntryMinutes <= SHORT_WAIT_MINUTES
                ? SHORT_WAIT_TOLERANCE_MINUTES
                : LONG_WAIT_TOLERANCE_MINUTES;
    }

    /** Ate quando a saida e gratuita, para quem entra agora com esta espera prevista. */
    public static OffsetDateTime toleranceUntil(OffsetDateTime enteredAt, int waitAtEntryMinutes) {
        return enteredAt.plusMinutes(toleranceMinutes(waitAtEntryMinutes));
    }

    /** Valor pelo tempo que falta para a vez — sem olhar a tolerancia. */
    public static BigDecimal amountFor(int remainingMinutes) {
        if (remainingMinutes >= NO_PENALTY_FROM_MINUTES) {
            return NO_PENALTY;
        }
        return remainingMinutes <= SHORT_WAIT_MINUTES ? SHORT_WAIT_PENALTY : MEDIUM_WAIT_PENALTY;
    }

    /**
     * Quanto custa sair AGORA. Dentro da tolerancia, nada. {@code toleranceUntil} nulo e atendimento
     * anterior a regra: a tolerancia nao foi gravada e nao se inventa — vale o valor pelo tempo.
     */
    public static BigDecimal exitPenalty(OffsetDateTime now, OffsetDateTime toleranceUntil, int remainingMinutes) {
        if (toleranceUntil != null && now.isBefore(toleranceUntil)) {
            return NO_PENALTY;
        }
        return amountFor(remainingMinutes);
    }
}
