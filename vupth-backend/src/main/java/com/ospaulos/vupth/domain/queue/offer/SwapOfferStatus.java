package com.ospaulos.vupth.domain.queue.offer;

/**
 * Ciclo de vida de uma oferta ABERTA de troca de posicao (Client C13 / Partner S11).
 *
 * <ul>
 *   <li>{@code OPEN} — publicada e aguardando alguem elegivel aceitar.</li>
 *   <li>{@code ACCEPTED} — alguem aceitou; a troca foi executada (consumida).</li>
 *   <li>{@code CANCELLED} — o solicitante cancelou.</li>
 *   <li>{@code EXPIRED} — o solicitante saiu da fila (foi atendido / cancelou o atendimento). NAO
 *       expira por fechamento da loja (regra selada 2026-07-15).</li>
 * </ul>
 */
public enum SwapOfferStatus {
    OPEN,
    ACCEPTED,
    CANCELLED,
    EXPIRED
}
