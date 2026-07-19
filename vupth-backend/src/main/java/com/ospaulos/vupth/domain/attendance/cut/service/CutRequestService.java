package com.ospaulos.vupth.domain.attendance.cut.service;

import com.ospaulos.vupth.api.attendance.dto.request.CutRequestRequest;
import com.ospaulos.vupth.api.attendance.dto.response.CutRequestResponse;

import java.util.UUID;

/**
 * Pedido de corte (BUG5 C2 — "solicitar corte"). Fatia 1: o cliente cria o pedido quando a loja
 * esta fechada p/ novos (gate C1). Fatias 2/3 (aceite/recusa + auto-nega por timer) chegam depois.
 */
public interface CutRequestService {

    /** Cliente (por JWT) registra um pedido de corte. Loja precisa estar fechada agora (senao 409). */
    CutRequestResponse createCutRequest(CutRequestRequest request, UUID clientId);

    /**
     * Profissional dono ACEITA o pedido (fatia 2): nasce um Attendance SCHEDULED na cauda da fila,
     * o pedido vira ACCEPTED e o cliente e notificado. So PENDING e nao-expirado.
     */
    CutRequestResponse acceptCutRequest(UUID cutRequestId, UUID professionalId);

    /**
     * Profissional dono RECUSA o pedido (fatia 2): vira REFUSED e o cliente e notificado. So PENDING.
     */
    CutRequestResponse refuseCutRequest(UUID cutRequestId, UUID professionalId);
}
