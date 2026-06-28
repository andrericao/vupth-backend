package com.ospaulos.vupth.domain.queue.swap.service;

import com.ospaulos.vupth.api.queue.swap.dto.response.SwapRequestResponse;

import java.util.List;
import java.util.UUID;

/**
 * Troca de posicao na fila por consentimento mutuo (item #42). Um cliente (A) propoe a troca do seu
 * atendimento com o de outro cliente (B) da mesma fila; a troca dos {@code scheduledTime} so efetiva
 * quando B aceita ({@code aceiteA && aceiteB}). Sem aceite dos dois, nada muda.
 */
public interface QueueSwapService {

    /** Cliente A propoe a troca do seu atendimento com o do cliente B (mesma fila). */
    SwapRequestResponse propose(UUID proposerAttendanceId, UUID targetAttendanceId, UUID requesterClientId);

    /** Cliente B responde a proposta: {@code accept=true} efetiva a troca; {@code false} recusa. */
    SwapRequestResponse respond(UUID swapRequestId, UUID requesterClientId, boolean accept);

    /** Propostas PENDING que envolvem o cliente (como proponente ou alvo). */
    List<SwapRequestResponse> listPendingForClient(UUID clientId);

    /**
     * Expira por evento: ao sair da fila (atendido/cancelado), as propostas PENDING que referenciam
     * o atendimento viram CANCELLED. Idempotente e tolerante (nao lanca).
     */
    void expirePendingForAttendance(UUID attendanceId);
}
