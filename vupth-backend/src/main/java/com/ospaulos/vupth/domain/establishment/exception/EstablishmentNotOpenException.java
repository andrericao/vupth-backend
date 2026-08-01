package com.ospaulos.vupth.domain.establishment.exception;

import java.util.UUID;

/**
 * Lançada quando um cliente tenta entrar na fila (walk-in imediato) de um establishment
 * que não está "aberto agora" (BUG5 parte C1 — gate de entrada).
 *
 * "Aberto agora" efetivo = {@code open_status == OPEN} <b>e</b> horário atual dentro de
 * {@code [opening_time, closing_time)} (ver {@link com.ospaulos.vupth.domain.establishment.model.Establishment#isOpenNow()}).
 * Fora do horário (ou com a loja fechada) o cliente <b>não entra sozinho</b> na fila viva; a
 * fila existente continua sendo atendida, e o caminho de "solicitar corte" (C2) fica para depois.
 *
 * Não bloqueia a INTENÇÃO de fila (#40, entrada com {@code targetTime} futuro), que mira um
 * horário dentro do expediente. Guest (walk-in adicionado pela loja) também não passa por aqui.
 *
 * Mapeada para HTTP 409 com code ESTABLISHMENT_NOT_OPEN.
 */
public class EstablishmentNotOpenException extends RuntimeException {
    public EstablishmentNotOpenException(UUID id) {
        super("Establishment " + id + " não está aberto agora: não recebe novas entradas na fila.");
    }
}
