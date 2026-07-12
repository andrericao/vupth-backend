package com.ospaulos.vupth.domain.queue.swap;

/**
 * Estado de uma proposta de troca de posicao na fila (item #42 — troca cliente<->cliente
 * por consentimento mutuo).
 *
 * <ul>
 *   <li>{@code PENDING} — o proponente (cliente A) ja consentiu ao propor; aguarda a resposta do alvo (cliente B).</li>
 *   <li>{@code ACCEPTED} — o alvo aceitou: {@code aceiteA && aceiteB} -> os {@code scheduledTime} foram trocados.</li>
 *   <li>{@code REJECTED} — o alvo recusou: nada muda na fila.</li>
 *   <li>{@code CANCELLED} — a proposta deixou de valer por evento (um dos atendimentos saiu da fila:
 *       atendido/cancelado), sem que o alvo tenha respondido.</li>
 * </ul>
 */
public enum SwapRequestStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    CANCELLED
}
