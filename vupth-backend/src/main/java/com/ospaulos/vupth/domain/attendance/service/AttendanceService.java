package com.ospaulos.vupth.domain.attendance.service;

import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceGuestRequest;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInProgressResponse;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;
import com.ospaulos.vupth.api.attendance.dto.response.AttendancePausedResponse;
import com.ospaulos.vupth.api.attendance.dto.response.IntentionResponse;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;

import com.ospaulos.vupth.api.attendance.dto.response.AttendancePageResponse;
import com.ospaulos.vupth.api.attendance.dto.response.ClientAttendanceResponse;
import com.ospaulos.vupth.api.attendance.dto.response.StoreAttendanceResponse;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AttendanceService {

    public AttendanceInQueueResponse getById(UUID attendanceId);

    public Attendance createAttendance(AttendanceRequest request,
                                       Professional professional,
                                       Establishment establishment,
                                       Client client);

    public AttendanceInQueueResponse createAttendance(AttendanceRequest request);

    public Attendance createGuestAttendance(AttendanceGuestRequest request, Professional professional, Establishment establishment);

    public void startAttendance(Attendance attendance, Professional professional);

    public void finishAttendance(Attendance attendance);

    public AttendanceInQueueResponse cancelAttendance(Attendance attendance);

    /** Sem ciencia: so sai se a saida nao gerar multa (D4). */
    default AttendanceInQueueResponse cancelAttendance(UUID attendance, UUID clientId) {
        return cancelAttendance(attendance, clientId, null);
    }

    /**
     * Cliente desiste da vez. Se sair agora gera multa, {@code acknowledgedPenalty} e o "Sim" do aviso:
     * precisa ser igual ou maior que o valor de agora, senao 409 EXIT_PENALTY_NOT_ACKNOWLEDGED com o valor.
     */
    AttendanceInQueueResponse cancelAttendance(UUID attendance, UUID clientId, java.math.BigDecimal acknowledgedPenalty);

    /** Sem ciencia: so sai se a saida nao gerar multa (D4). */
    default AttendanceInQueueResponse leaveQueue(UUID attendanceId, UUID clientId) {
        return leaveQueue(attendanceId, clientId, null);
    }

    /** Cliente sai da fila — a mesma ciencia do {@link #cancelAttendance(UUID, UUID, java.math.BigDecimal)}. */
    AttendanceInQueueResponse leaveQueue(UUID attendanceId, UUID clientId, java.math.BigDecimal acknowledgedPenalty);

    /**
     * Item #43 (Opcao A) — remanejar por horario-alvo. O cliente com espera longa reagenda a
     * propria vez escolhendo um {@code targetTime}: o atendimento (que ja esta na fila em
     * {@code SCHEDULED}) vira INTENCAO ({@code isIntent=true}), reusando a maquina do #40
     * (viabilidade {@code isTargetFeasible} -> 422 se infactivel; encaixe cronologico
     * {@code assignAgendaSlot}). Só o proprio cliente (por JWT) e apenas em {@code SCHEDULED}.
     */
    public AttendanceInQueueResponse rescheduleWithTargetTime(UUID attendanceId, UUID clientId, java.time.OffsetDateTime targetTime);

    /**
     * Item #43 (Opcao B) — ir para o fim da fila. O cliente com espera longa desce a propria
     * vez para depois do ultimo em espera (empurra o {@code scheduledTime}), sem sair da fila,
     * reusando o padrao de reposicao do #19. Limpa qualquer intencao ({@code isIntent}/target).
     * Só o proprio cliente (por JWT) e apenas em {@code SCHEDULED}. No-op se ja for o ultimo.
     */
    public AttendanceInQueueResponse moveToEndOfQueue(UUID attendanceId, UUID clientId);

    /**
     * Adicionar servico a atendimento ja na fila (adicionar-servico-na-fila, Client C8). So o proprio
     * cliente (JWT), so em {@code SCHEDULED}. Os itens precisam ser do profissional do atendimento; os
     * que ja estao nele sao ignorados. O tempo a mais so cabe se ninguem estiver depois na fila e
     * nenhuma intencao comecar antes do novo fim (regra do Andre, 15/09) — senao 409
     * {@code ATTENDANCE_NO_ROOM_FOR_SERVICE} e nada muda.
     */
    public AttendanceInQueueResponse addServiceItems(UUID attendanceId, UUID clientId, List<UUID> serviceItemIds);

    /**
     * Partner S7 — promover ao 1o lugar. O PROFISSIONAL dono da fila move um atendimento em espera
     * para o indice 0 (proximo a ser atendido), empurrando os demais — sem inicia-lo. Inverso do
     * {@link #moveToEndOfQueue}: o {@code scheduledTime} recebe {@code cabeca.scheduledTime - 1min}.
     * Só o profissional dono (por JWT) e apenas em {@code SCHEDULED}. No-op se ja for o 1o.
     */
    public AttendanceInQueueResponse promoteToFrontOfQueue(UUID attendanceId, UUID professionalId);

    /**
     * Cria um {@code Attendance} SCHEDULED na CAUDA da fila a partir de um pedido de corte aceito
     * (BUG5 C2 fatia 2). O aceite explicito do profissional e a autorizacao — bypassa o gate C1
     * (a loja esta fechada p/ novos, mas o profissional consentiu). Reusa o meal-lock (#37).
     */
    public Attendance createScheduledFromCutRequest(Professional professional, Establishment establishment,
                                                    Client client, List<ServiceItem> serviceItems);

    /**
     * Cria um {@code Attendance} SCHEDULED numa POSICAO especifica da fila, a partir de uma oferta
     * aberta de troca aceita por quem estava na fila (Client C13, fatia 2 — solicitante FORA da fila).
     * O {@code scheduledTime} recebe o slot CEDIDO por quem aceitou (que ja foi movido pro fim antes
     * desta chamada), e nao "agora" como no walk-in. O aceite do outro cliente e a autorizacao —
     * bypassa o gate C1 pelo mesmo motivo do corte aceito. Reusa o meal-lock (#37).
     */
    /**
     * Pedido de corte para OUTRO dia aceito (aceite-vira-agendamento, Andre 05/10): nasce uma
     * {@code INTENTION} naquele dia e horario, pelo MESMO algoritmo da intencao do cliente — o
     * expediente, o horizonte e o "cheio" valem igual (422/409), e o pedido continua PENDING se recusar.
     */
    public Attendance createIntentionFromCutRequest(Professional professional, Establishment establishment,
                                                    Client client, List<ServiceItem> serviceItems,
                                                    java.time.OffsetDateTime desiredTime);

    public Attendance createScheduledFromSwapOffer(Professional professional, Establishment establishment,
                                                   Client client, List<ServiceItem> serviceItems,
                                                   java.time.OffsetDateTime slot);

    /**
     * Backlog #4 — manifestar intencao de atendimento (status {@code INTENTION}). O cliente
     * registrado (por JWT) pede para ser atendido num horario PRETENDIDO futuro ({@code desiredTime}),
     * tipicamente com a loja fechada ou o dia cheio. Nasce FORA da fila viva (nao aparece em
     * professionals-with-queue) e sera promovido a {@code SCHEDULED} perto do horario (fatia 3).
     * Valida: horario no futuro (400); dentro do horizonte (422 {@code INTENTION_HORIZON_EXCEEDED});
     * dentro do expediente {@code [opening, closing)} (422 {@code INTENTION_OUTSIDE_BUSINESS_HOURS});
     * cabe antes do fechamento (409 {@code PROFESSIONAL_AGENDA_FULL} = CHEIO). A resposta traz a
     * estimativa de atendimento considerando as outras intencoes (encaixe cronologico, sem reservar).
     */
    public IntentionResponse createIntention(UUID professionalId, List<UUID> serviceItemIds,
                                             java.time.OffsetDateTime desiredTime, UUID clientId);

    /**
     * Backlog #4 (fatia 3) — editar uma intencao. O cliente dono (por JWT) altera o {@code desiredTime}.
     * Passa pelo MESMO algoritmo do create (ponto unico de entrada): revalida futuro/expediente/horizonte/
     * invariante-do-fim-da-fila e recalcula a estimativa. So enquanto {@code status = INTENTION}
     * (400 {@code ATTENDANCE_INVALID_STATUS} se ja promovido/cancelado); 403 se nao for o dono.
     */
    public IntentionResponse updateIntention(UUID attendanceId, java.time.OffsetDateTime desiredTime, UUID clientId);

    /**
     * Backlog #4 (fatia 3) — cancelar uma intencao (status {@code INTENTION -> CANCELLED}). Apenas o
     * cliente dono (por JWT). So enquanto {@code status = INTENTION} (400 {@code ATTENDANCE_INVALID_STATUS}
     * se ja promovido/cancelado); 403 se nao for o dono. Como a INTENTION esta fora da fila viva, o
     * cancelamento nao mexe em fila/broadcast.
     */
    public void cancelIntention(UUID attendanceId, UUID clientId);

    /**
     * Backlog #4 (fatia 2 — visao de agenda) — agenda de UM profissional: as suas intencoes futuras
     * ({@code INTENTION}, fora da fila viva), congeladas no estimado da criacao, ordenadas por horario,
     * mais o "quadro" da barra de encaixe (1 frame: {@code projectedQueueEnd} + expediente). O front
     * agrupa por dia. Read-only.
     */
    public com.ospaulos.vupth.api.attendance.dto.response.IntentionAgendaResponse getProfessionalAgenda(UUID professionalId);

    /**
     * Barra de encaixe (#3 / Client B2) — agenda PUBLICA de um profissional: o mesmo quadro da agenda
     * dele e os horarios reservados por intencoes como blocos ANONIMOS (so inicio e duracao). Read-only.
     */
    public com.ospaulos.vupth.api.attendance.dto.response.PublicAgendaResponse getPublicAgenda(UUID professionalId);

    /**
     * Backlog #4 (fatia 2 — visao de agenda) — agenda do CLIENTE: os proprios agendamentos
     * ({@code INTENTION}) em qualquer loja, ordenados por horario, mais um frame por profissional
     * distinto. Mesma forma da agenda do profissional. Read-only.
     */
    public com.ospaulos.vupth.api.attendance.dto.response.IntentionAgendaResponse getClientAgenda(UUID clientId);

    /**
     * Backlog #4 (fatia 3 — promocao) — promove UMA intencao a fila viva ({@code INTENTION -> SCHEDULED}).
     * Chamado pelo gatilho event-driven ({@code IntentionPromotionScheduler}) quando o horario se aproxima.
     * <b>Flip atomico</b>: re-le o atendimento e so promove se ainda estiver {@code INTENTION} (corrida com
     * cancelar/editar/outra promocao = no-op, devolve {@code Optional.empty()}, auto-limpante). Ao promover,
     * o {@code scheduledTime} recebe o instante atual — a intencao entra na CAUDA da fila viva (a posicao do
     * ETA daquele instante, Modelo A, sem reservar slot) — e as refeicoes a frente sao travadas no ingresso.
     * A due-check ({@code now >= desiredTime - 15min}) é responsabilidade do scheduler (fonte da verdade = a
     * re-query da cabeca); aqui so garante-se a atomicidade do flip.
     *
     * @return dados minimos para o scheduler notificar (WS + push) fora da transacao, ou vazio se nao promoveu.
     */
    public java.util.Optional<PromotionResult> promoteIntention(UUID attendanceId);

    /** Resultado minimo de uma promocao, para o scheduler notificar (WS/push) apos o commit. */
    record PromotionResult(UUID attendanceId, UUID establishmentId, UUID clientId) {}

    /**
     * Item #6 — avaliar um atendimento concluido. Apenas o {@code clientId} dono do atendimento
     * (por JWT) e apenas com {@code status = FINISHED}. Bloqueia o 2o voto (409). Convidado (sem
     * conta) nao avalia. Se o atendimento tem exatamente 1 servico, a nota alimenta o profissional
     * E o {@code ServiceItem}; com 2+ servicos, so o profissional.
     */
    public com.ospaulos.vupth.api.attendance.dto.response.AttendanceRatingResponse rateAttendance(
            UUID attendanceId, UUID clientId,
            com.ospaulos.vupth.api.attendance.dto.request.AttendanceRatingRequest request);

    public AttendanceInQueueResponse removeFromQueue(UUID attendanceId, UUID professionalId);

    public AttendanceInQueueResponse getNextAttendance(Professional professional, Establishment establishment);

    public AttendancePausedResponse pauseAttendance(Attendance attendance);

    public AttendanceInProgressResponse resumeAttendance(Attendance attendance);

    public double calculateWeeklyAverageDuration(Professional professional, LocalDate weekStart, LocalDate weekEnd);

    public Object buildAttendanceResponse(Attendance attendance);

    public AttendanceInProgressResponse buildAttendanceInProgressResponse(Attendance attendance);

    public AttendanceInProgressResponse buildFinishedAttendanceResponse(Attendance attendance);

    public AttendancePausedResponse buildPausedAttendance(Attendance attendance);

    public AttendanceInQueueResponse buildAttendanceInQueueResponse(Attendance attendance);

    /** A lista da LOJA (profissional ou dono): a divisao do preco, sem multa nem lucro do app (DEC-94). */
    public List<StoreAttendanceResponse> listAttendances(
            UUID userId,
            UUID establishmentId,
            UUID professionalId,
            LocalDate dateFrom,
            LocalDate dateTo,
            AttendanceStatus status);

    /**
     * Historico paginado da loja (historico-rapido): os mesmos filtros e a mesma ordem de
     * {@link #listAttendances}, cortados no banco.
     */
    public AttendancePageResponse<StoreAttendanceResponse> listAttendancesPage(
            UUID userId,
            UUID establishmentId,
            UUID professionalId,
            LocalDate dateFrom,
            LocalDate dateTo,
            AttendanceStatus status,
            com.ospaulos.vupth.domain.establishment.valueObject.ShowcasePage page);

    /** O historico do PROPRIO cliente: o corte e a multa, sem o valor do profissional nem a margem do app (DEC-94). */
    public List<ClientAttendanceResponse> listClientAttendances(
            UUID clientId,
            AttendanceStatus status,
            LocalDate dateFrom,
            LocalDate dateTo);

    public AttendancePageResponse<ClientAttendanceResponse> listClientAttendancesPage(
            UUID clientId,
            AttendanceStatus status,
            LocalDate dateFrom,
            LocalDate dateTo,
            com.ospaulos.vupth.domain.establishment.valueObject.ShowcasePage page);
}
