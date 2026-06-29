package com.ospaulos.vupth.domain.client.model;

/**
 * Tipo de penalidade (strike) registrada contra um cliente (item #39).
 *
 * <ul>
 *   <li>{@code LATE_CANCEL} — cliente cancelou um atendimento SCHEDULED com o ETA da sua vez
 *       a menos de 1h (desorganiza a fila / afeta o proximo). Unico tipo da Fase 1.</li>
 *   <li>{@code NO_SHOW} — reservado para a escalada de no-show (ausente reincidente removido da
 *       fila + strike); ainda nao emitido nesta entrega.</li>
 * </ul>
 */
public enum PenaltyType {
    LATE_CANCEL,
    NO_SHOW
}
