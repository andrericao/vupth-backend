package com.ospaulos.vupth.domain.queue.swap.exception;

/**
 * Troca de posicao na fila (item #42). A troca nao e permitida pelo estado dos atendimentos:
 * filas diferentes (profissionais distintos), atendimento que nao esta SCHEDULED (em andamento/
 * cancelado/finalizado), proposta com o proprio atendimento, ou proposta que ja nao esta mais
 * PENDING ao responder. Mapeada para HTTP 422 com code QUEUE_SWAP_NOT_ALLOWED.
 */
public class SwapNotAllowedException extends RuntimeException {
    public SwapNotAllowedException(String message) {
        super(message);
    }
}
