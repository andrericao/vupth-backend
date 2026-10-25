package com.ospaulos.vupth.domain.attendance.cut.service;

import com.ospaulos.vupth.api.attendance.dto.request.CutRequestRequest;
import com.ospaulos.vupth.api.attendance.dto.response.CutRequestResponse;

import java.util.List;
import java.util.UUID;

/**
 * Pedido de corte (BUG5 C2 — "solicitar corte"). Fatia 1: o cliente cria o pedido quando a loja
 * esta fechada p/ novos (gate C1). Fatias 2/3 (aceite/recusa + auto-nega por timer) chegam depois.
 */
public interface CutRequestService {

    /** Cliente (por JWT) registra um pedido de corte. Loja precisa estar fechada agora (senao 409). */
    CutRequestResponse createCutRequest(CutRequestRequest request, UUID clientId);

    /**
     * Lista os pedidos PENDING dirigidos ao profissional (por JWT), do mais antigo ao mais novo.
     * Desbloqueia a UI do Partner: caminho de recuperacao quando o WS {@code CUT_REQUESTED} foi
     * perdido (app fechado) e fonte da lista de "pedidos aguardando decisao".
     */
    List<CutRequestResponse> listPendingForProfessional(UUID professionalId);

    /**
     * Profissional dono ACEITA o pedido (fatia 2): nasce um Attendance SCHEDULED na cauda da fila,
     * o pedido vira ACCEPTED e o cliente e notificado. So PENDING e nao-expirado.
     */
    CutRequestResponse acceptCutRequest(UUID cutRequestId, UUID professionalId);

    /**
     * Profissional dono RECUSA o pedido (fatia 2): vira REFUSED e o cliente e notificado. So PENDING.
     */
    CutRequestResponse refuseCutRequest(UUID cutRequestId, UUID professionalId);

    /**
     * Auto-nega (fatia 3): marca EXPIRED todos os pedidos PENDING ja vencidos ({@code expiresAt <= now})
     * e notifica cada cliente. Chamado pelo {@code CutRequestExpiryScheduler} (timer server-side), 100%
     * interno — sem endpoint REST. Devolve quantos foram expirados.
     */
    int expireDueCutRequests();

    /**
     * solicitar-agendamento: o profissional abriu um dia na agenda. Os pedidos PENDING para dia sem agenda liberada
     * cujo dia agora esta aberto vencem (EXPIRED) e o cliente e avisado para agendar direto. Devolve quantos venceram.
     */
    int expireRequestsForOpenedDays(UUID professionalId);
}
