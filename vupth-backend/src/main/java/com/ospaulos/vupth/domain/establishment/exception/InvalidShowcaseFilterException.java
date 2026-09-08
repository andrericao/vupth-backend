package com.ospaulos.vupth.domain.establishment.exception;

/**
 * Um filtro/ordenacao da vitrine veio com valor impossivel — {@code GET /establishment?sort=},
 * {@code ?maxWaitMinutes=} (06/09).
 *
 * <p>Recusar segue o criterio ja usado pelo {@code radiusKm <= 0} e pelo {@code uf} fora de sigla:
 * o chamador <b>disse o que queria</b> e disse algo que nao existe. Deixar passar devolveria uma
 * lista plausivel — ordenada por outro criterio, ou vazia — sob o rotulo que o usuario escolheu na
 * tela, e nem ele nem o front teriam como notar.</p>
 *
 * <p>Diferente do {@code radiusKm} <b>sem origem</b>, que e ignorado sem erro: la o app manda um
 * valor guardado de antes sem ninguem ter pedido nada.</p>
 */
public class InvalidShowcaseFilterException extends RuntimeException {

    public InvalidShowcaseFilterException(String message) {
        super(message);
    }
}
