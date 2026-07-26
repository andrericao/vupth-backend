package com.ospaulos.vupth.domain.queue.offer;

/**
 * Direcao de uma oferta ABERTA de troca de posicao (Client C13 / Partner S11).
 *
 * <ul>
 *   <li>{@code EARLIER} — o solicitante quer ADIANTAR (ir para mais perto do inicio). A oferta
 *       aparece para quem esta A FRENTE dele (que pode ceder o lugar).</li>
 *   <li>{@code LATER} — o solicitante quer ATRASAR (ir para mais perto do fim). A oferta aparece
 *       para quem esta ATRAS dele (que pode subir).</li>
 * </ul>
 */
public enum SwapOfferDirection {
    EARLIER,
    LATER
}
