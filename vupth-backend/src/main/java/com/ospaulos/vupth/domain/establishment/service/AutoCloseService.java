package com.ospaulos.vupth.domain.establishment.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Auto-fechamento por horario (S1 fatia 5 / BUG1 — "a loja nao pode dormir aberta").
 *
 * <p>Duas fases, ambas disparadas pelo {@code EstablishmentAutoCloseScheduler} (timer server-side
 * event-driven; o celular dorme, o backend nao):</p>
 * <ol>
 *   <li><b>Prompt + carencia</b> ({@link #promptDueClosings()}): profissional em expediente
 *       ({@code WORKING}/{@code ON_BREAK}) logo apos o {@code closing_time} → fecha a fila
 *       ({@code accepting_new=false}, drenagem) e marca {@code closing_grace_until = now+15min}.
 *       O chamador avisa o profissional (WS {@code CLOSING_PROMPT} + push, "quer fechar?") e os
 *       clientes na fila ({@code QUEUE_CLOSING}).</li>
 *   <li><b>Carencia vencida</b> ({@link #applyDueGraces()}): sem sinalizacao em 15min → sem nada
 *       ativo vira {@code OFF} (sendo o ultimo, a loja fecha via derivacao); com atendimento/fila
 *       a carencia e limpa e o auto-OFF fica com o fim da drenagem (fatia 3). Se o expediente foi
 *       ESTENDIDO (closing_time mudou e "agora" voltou a cair dentro), a carencia e limpa sem
 *       fechar nada (auto-correcao — e o caminho oficial de "continuar aberto").</li>
 * </ol>
 */
public interface AutoCloseService {

    /** Cliente na fila do profissional no momento do fechamento automatico (anuncio QUEUE_CLOSING). */
    record WaitingClient(UUID attendanceId, UUID clientId) {}

    /** Profissional que acabou de entrar em carencia (prompt "quer fechar?"). */
    record PromptedProfessional(UUID professionalId, UUID establishmentId,
                                OffsetDateTime graceUntil, List<WaitingClient> waitingClients) {}

    /** Resultado da carencia vencida de um profissional. */
    record GraceOutcome(UUID professionalId, UUID establishmentId, boolean autoOff) {}

    /** Fase 1 — fecha a fila + arma a carencia de quem esta em expediente logo apos o fechamento. */
    List<PromptedProfessional> promptDueClosings();

    /** Fase 2 — aplica as carencias vencidas (auto-OFF / drenagem / auto-correcao). */
    List<GraceOutcome> applyDueGraces();

    /**
     * Proximo instante em que ha trabalho: a menor carencia pendente ou o proximo
     * {@code closing_time} de uma loja com presenca. Vazio = nada armado (timer ocioso).
     */
    Optional<OffsetDateTime> nextEventAt();
}
