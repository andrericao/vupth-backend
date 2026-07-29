package com.ospaulos.vupth.domain.queue.offer.service;

import com.ospaulos.vupth.api.queue.offer.dto.request.SwapOfferCreateRequest;
import com.ospaulos.vupth.api.queue.offer.dto.response.SwapOfferResponse;

import java.util.List;
import java.util.UUID;

/**
 * Oferta ABERTA de troca de posicao na fila (Client C13 / Partner S11 — modelo fechado 2026-07-15).
 * Distinta do {@code QueueSwapService} (#42, DIRIGIDO): aqui a oferta e publicada e QUALQUER cliente
 * elegivel (por posicao/direcao na mesma fila) pode aceitar. O partner e passivo (so reflete a fila
 * pos-troca). Nunca gera multa.
 */
public interface SwapOfferService {

    /**
     * Publica uma oferta. Na fila: {@code {attendanceId, direction}} (posicao explicita). Fora da fila:
     * {@code {professionalId, serviceItemIds, direction: EARLIER}} (posicao implicita = fim).
     */
    SwapOfferResponse create(SwapOfferCreateRequest request, UUID requesterClientId);

    /** Ofertas OPEN que o {@code callerClient} pode aceitar na fila do profissional (filtro por posicao/direcao). */
    List<SwapOfferResponse> listAcceptable(UUID callerClientId, UUID professionalId);

    /** Ofertas OPEN publicadas pelo proprio cliente ("meus pedidos de troca", para cancelar). */
    List<SwapOfferResponse> listMine(UUID requesterClientId);

    /**
     * O {@code callerClient} aceita a oferta e a troca e executada, sempre 1-por-1 (so os dois se
     * movem), notificando os DOIS lados. Solicitante NA fila: os {@code scheduledTime} sao trocados.
     * Solicitante FORA da fila: quem aceitou vai pro fim e o solicitante nasce na vaga cedida.
     */
    SwapOfferResponse accept(UUID offerId, UUID callerClientId);

    /** O solicitante cancela a propria oferta (OPEN -> CANCELLED). */
    void cancel(UUID offerId, UUID requesterClientId);

    /**
     * Expira por evento: ao sair da fila (atendido/cancelado), as ofertas OPEN cujo solicitante e o
     * atendimento viram EXPIRED. Idempotente e tolerante (nao lanca). NAO expira por fechamento.
     */
    void expireOpenOffersForAttendance(UUID attendanceId);
}
