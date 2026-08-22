package com.ospaulos.vupth.domain.address.exception;

/**
 * O parametro {@code uf} da listagem publica nao e uma sigla de estado (backlog #29 / fatia C do #2).
 *
 * <p>Recusar em vez de deixar passar segue o mesmo criterio do {@code radiusKm <= 0}: a coluna
 * {@code address.state} tem 2 caracteres, entao qualquer coisa fora desse formato — "Rio de
 * Janeiro", "SP1", "R" — <b>nunca casa com nada</b> e devolveria lista vazia sempre. O usuario nao
 * distingue "nao ha loja no meu estado" de "o app quebrou", e o front nao teria como perceber que
 * mandou o campo errado.</p>
 */
public class InvalidUfException extends RuntimeException {

    public InvalidUfException(String message) {
        super(message);
    }
}
