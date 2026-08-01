package com.ospaulos.vupth.domain.queue.offer.exception;

import java.util.UUID;

/**
 * Oferta aberta de troca de posicao (Client C13). Oferta inexistente. Mapeada para HTTP 404.
 */
public class SwapOfferNotFoundException extends RuntimeException {
    public SwapOfferNotFoundException(UUID id) {
        super("Swap offer not found: " + id);
    }
}
