package com.ospaulos.vupth.domain.queue.swap.exception;

import com.ospaulos.vupth.domain.queue.swap.SwapDurationRule;

/**
 * D14: as duracoes dos dois atendimentos diferem mais que {@link SwapDurationRule#MAX_GAP_MINUTES}
 * minutos. 422 {@code QUEUE_SWAP_DURATION_INCOMPATIBLE}; a fila nao muda.
 */
public class SwapDurationIncompatibleException extends RuntimeException {
    public SwapDurationIncompatibleException(int minutesA, int minutesB) {
        super("A troca so vale entre atendimentos com ate " + SwapDurationRule.MAX_GAP_MINUTES
                + " minutos de diferenca de duracao (" + minutesA + " x " + minutesB + " min).");
    }
}
