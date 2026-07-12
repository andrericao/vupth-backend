package com.ospaulos.vupth.api.queue.swap.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Pedido de troca de posicao na fila (item #42). O cliente A (autenticado) propoe a troca do seu
 * atendimento {@code proposerAttendanceId} com o atendimento {@code targetAttendanceId} (cliente B,
 * mesma fila). A propriedade do proposer e validada contra o token; o body so carrega os ids.
 */
public record SwapRequestCreateRequest(
        @NotNull UUID proposerAttendanceId,
        @NotNull UUID targetAttendanceId
) {}
