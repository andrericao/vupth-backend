package com.ospaulos.vupth.api.queue.offer.dto.request;

import com.ospaulos.vupth.domain.queue.offer.SwapOfferDirection;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Cria uma oferta ABERTA de troca de posicao (Client C13).
 *
 * <ul>
 *   <li><b>Na fila</b>: {@code { attendanceId, direction }} — o solicitante ja tem um atendimento
 *       SCHEDULED na fila.</li>
 *   <li><b>Fora da fila</b>: {@code { professionalId, serviceItemIds, direction: EARLIER }} — o
 *       solicitante ainda nao entrou; posicao implicita = fim.</li>
 * </ul>
 *
 * Exatamente um entre {@code attendanceId} e {@code professionalId} deve vir preenchido (validado
 * no service). {@code direction} e sempre obrigatorio.
 */
public record SwapOfferCreateRequest(
        UUID attendanceId,
        UUID professionalId,
        List<UUID> serviceItemIds,
        @NotNull SwapOfferDirection direction
) {
}
