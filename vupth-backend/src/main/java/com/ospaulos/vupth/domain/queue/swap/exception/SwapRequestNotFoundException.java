package com.ospaulos.vupth.domain.queue.swap.exception;

import java.util.UUID;

/**
 * Troca de posicao na fila (item #42). Proposta de troca inexistente. Mapeada para HTTP 404.
 */
public class SwapRequestNotFoundException extends RuntimeException {
    public SwapRequestNotFoundException(UUID id) {
        super("Swap request not found: " + id);
    }
}
