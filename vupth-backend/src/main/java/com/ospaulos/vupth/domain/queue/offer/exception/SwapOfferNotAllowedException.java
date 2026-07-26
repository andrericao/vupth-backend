package com.ospaulos.vupth.domain.queue.offer.exception;

/**
 * Oferta aberta de troca de posicao (Client C13). A oferta ou o aceite nao sao permitidos pelo estado:
 * atendimento que nao esta aguardando (SCHEDULED/LATE), oferta que nao esta mais OPEN, aceitante que
 * nao e elegivel pela posicao/direcao, oferta de FORA da fila sem profissional/servicos ou com direcao
 * diferente de EARLIER, aceitar a propria oferta, etc. Mapeada para HTTP 422 com code
 * QUEUE_SWAP_OFFER_NOT_ALLOWED.
 */
public class SwapOfferNotAllowedException extends RuntimeException {
    public SwapOfferNotAllowedException(String message) {
        super(message);
    }
}
