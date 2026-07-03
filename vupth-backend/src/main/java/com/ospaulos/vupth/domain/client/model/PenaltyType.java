package com.ospaulos.vupth.domain.client.model;

/**
 * Tipo de penalidade (strike) registrada contra um cliente (item #39).
 *
 * <ul>
 *   <li>{@code LATE_CANCEL} — cliente cancelou um atendimento SCHEDULED com o ETA da sua vez
 *       a menos de 1h (desorganiza a fila / afeta o proximo). Unico tipo da Fase 1.</li>
 *   <li>{@code NO_SHOW} — escalada de no-show: cliente que ja estava LATE (#19, caiu de posicao)
 *       e nao compareceu ao ser chamado de novo e removido da fila (CANCELLED) + recebe strike.
 *       Emitido por {@code PATCH /attendances/{id}/no-show}.</li>
 * </ul>
 */
public enum PenaltyType {
    LATE_CANCEL,
    NO_SHOW
}
