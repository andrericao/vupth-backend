package com.ospaulos.vupth.domain.queue.swap.exception;

/**
 * Troca de posicao na fila (item #42). Ja existe uma proposta PENDING entre os dois atendimentos
 * (em qualquer direcao). Mapeada para HTTP 409 com code QUEUE_SWAP_ALREADY_PENDING.
 */
public class SwapAlreadyPendingException extends RuntimeException {
    public SwapAlreadyPendingException() {
        super("Ja existe uma proposta de troca pendente entre estes dois atendimentos.");
    }
}
