package com.ospaulos.vupth.domain.attendance.repository;

import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttendanceRepository extends JpaRepository<Attendance, UUID> {

    /**
     * Eager load of all LAZY associations needed for serialization.
     * Use this instead of findById() whenever the result will be serialized to JSON
     * (i.e. in controllers/services that return DTOs built from the entity).
     * Without JOIN FETCH, Jackson triggers LazyInitializationException after the session closes.
     */
    @Query("""
        SELECT a FROM Attendance a
        LEFT JOIN FETCH a.client c
        LEFT JOIN FETCH c.baseUser
        LEFT JOIN FETCH a.professional p
        LEFT JOIN FETCH p.baseUser
        LEFT JOIN FETCH a.establishment
        LEFT JOIN FETCH a.serviceItems
        WHERE a.id = :id
    """)
    Optional<Attendance> findByIdEager(@Param("id") UUID id);

    // Finds attendances with a given service item, status and creation date after a threshold
    List<Attendance> findByServiceItems_IdAndStatusAndCreatedAtAfter(
            UUID serviceItemId, AttendanceStatus status, LocalDateTime date
    );

    // Only FINISHED attendances with ONE service item for accurate average calculation
    @Query("""
        SELECT a FROM Attendance a 
        JOIN a.serviceItems s 
        WHERE s.id = :serviceItemId 
          AND a.status = :status 
          AND SIZE(a.serviceItems) = 1
        """)
    List<Attendance> findSingleServiceAttendances(UUID serviceItemId, AttendanceStatus status);

    @Query("SELECT a FROM Attendance a WHERE :serviceItem MEMBER OF a.serviceItems AND " +
            "a.status = :status AND " +
            "a.scheduledTime BETWEEN :start AND :end")
    List<Attendance> findByServiceItemsContainingAndStatusAndScheduledTimeBetween(
            @Param("serviceItem") ServiceItem serviceItem,
            @Param("status") AttendanceStatus status,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    List<Attendance> findByProfessionalAndStatusAndScheduledTimeBetween(
                Professional professional,
                AttendanceStatus status,
                LocalDateTime start,
                LocalDateTime end);

    @Query("""
        SELECT a FROM Attendance a
        WHERE a.professional.id = :professionalId
          AND a.establishment.id = :establishmentId
          AND (
              a.scheduledTime >= :fromTime
              OR (a.startTime IS NOT NULL AND a.startTime >= :fromTime)
          )
        ORDER BY a.scheduledTime ASC
    """)
    //Ajuste: AND a.status IN ('SCHEDULED', 'IN_PROGRESS', 'WAITING')
    List<Attendance> findQueueByProfessionalAndEstablishment(
            @Param("professionalId") UUID professionalId,
            @Param("establishmentId") UUID establishmentId,
            @Param("fromTime") LocalDateTime fromTime
    );

    @Query("""
        SELECT a FROM Attendance a
        WHERE a.professional.id = :professionalId
        AND a.status = :status
        ORDER BY a.createdAt ASC
    """)
    List<Attendance> findByProfessionalAndStatus(
            @Param("professionalId") UUID professionalId,
            @Param("status") AttendanceStatus status
    );


    /**
     * Find os attendances em espera para formar a fila. Inclui SCHEDULED e LATE (item #19):
     * o cliente marcado como atrasado NAO sai da fila — apenas cai de posicao (scheduledTime
     * empurrado), entao continua aparecendo aqui, ordenado por scheduledTime.
     * JOIN FETCH evita LazyInitializationException ao serializar client/professional/serviceItems.
     */
    @Query("""
        SELECT DISTINCT a FROM Attendance a
        LEFT JOIN FETCH a.client c
        LEFT JOIN FETCH c.baseUser
        LEFT JOIN FETCH a.professional p
        LEFT JOIN FETCH p.baseUser
        LEFT JOIN FETCH a.establishment
        LEFT JOIN FETCH a.serviceItems
        WHERE a.professional.id = :professionalId
          AND a.establishment.id = :establishmentId
          AND a.status IN ('SCHEDULED', 'LATE')
        ORDER BY a.scheduledTime ASC
    """)
    List<Attendance> findWaitingQueueByProfessionalAndEstablishment(
            @Param("professionalId") UUID professionalId,
            @Param("establishmentId") UUID establishmentId
    );

    /**
     * Intencoes de atendimento (backlog #4 / status INTENTION) de um profissional, ordenadas pelo
     * horario pretendido. NAO fazem parte da fila viva — servem para o encaixe cronologico da
     * estimativa de uma nova intencao (sem reservar posicao) e para a visao de agenda (fatia 2).
     * JOIN FETCH evita LazyInitialization ao serializar client/serviceItems.
     */
    @Query("""
        SELECT DISTINCT a FROM Attendance a
        LEFT JOIN FETCH a.client c
        LEFT JOIN FETCH c.baseUser
        LEFT JOIN FETCH a.professional p
        LEFT JOIN FETCH a.establishment
        LEFT JOIN FETCH a.serviceItems
        WHERE a.professional.id = :professionalId
          AND a.status = 'INTENTION'
        ORDER BY a.desiredTime ASC
    """)
    List<Attendance> findIntentionsByProfessional(@Param("professionalId") UUID professionalId);

    /**
     * Intencoes de atendimento (status INTENTION) de um CLIENTE, em qualquer loja, ordenadas pelo
     * horario pretendido. Base da visao de agenda do cliente ("meus agendamentos", fatia 2) — os
     * agendamentos futuros que ainda nao entraram na fila viva. JOIN FETCH evita LazyInitialization
     * ao serializar professional/establishment/serviceItems.
     */
    @Query("""
        SELECT DISTINCT a FROM Attendance a
        LEFT JOIN FETCH a.professional p
        LEFT JOIN FETCH a.establishment
        LEFT JOIN FETCH a.serviceItems
        WHERE a.client.id = :clientId
          AND a.status = 'INTENTION'
        ORDER BY a.desiredTime ASC
    """)
    List<Attendance> findIntentionsByClient(@Param("clientId") UUID clientId);

    /**
     * Intencoes de atendimento (status INTENTION) de TODOS os profissionais, ordenadas pelo horario
     * pretendido (menor primeiro). Base do gatilho de promocao event-driven (backlog #4, fatia 3):
     * a "cabeca" da agenda global e o primeiro elemento. Sem JOIN FETCH de colecoes — o scheduler so
     * le {@code id}/{@code desiredTime} (campos basicos), e a promocao re-carrega via {@code findByIdEager}.
     * Usar com {@code Pageable} (LIMIT 1) via {@link #findEarliestIntention()}.
     */
    @Query("""
        SELECT a FROM Attendance a
        WHERE a.status = 'INTENTION'
        ORDER BY a.desiredTime ASC
    """)
    List<Attendance> findIntentionsOrderedByDesiredTime(org.springframework.data.domain.Pageable pageable);

    /**
     * A intencao de MENOR {@code desiredTime} em todo o sistema (a "cabeca" da agenda) ou vazio se
     * nao houver nenhuma. Fonte da verdade do gatilho de promocao — o timer e apenas um palpite; a
     * cada disparo re-consulta aqui. {@code ORDER BY desiredTime LIMIT 1} = O(log n) e sempre fresco.
     */
    default Optional<Attendance> findEarliestIntention() {
        List<Attendance> earliest = findIntentionsOrderedByDesiredTime(
                org.springframework.data.domain.PageRequest.of(0, 1));
        return earliest.isEmpty() ? Optional.empty() : Optional.of(earliest.get(0));
    }

    /**
     * Fila de espera de TODOS os profissionais de um establishment (SCHEDULED + LATE),
     * ordenada por profissional e horario — usada para detectar o "proximo" de cada fila
     * (push do item de notificacoes). JOIN FETCH evita LazyInitialization ao ler client/professional.
     */
    @Query("""
        SELECT DISTINCT a FROM Attendance a
        LEFT JOIN FETCH a.client c
        LEFT JOIN FETCH c.baseUser
        LEFT JOIN FETCH a.professional p
        LEFT JOIN FETCH a.establishment
        WHERE a.establishment.id = :establishmentId
          AND a.status IN ('SCHEDULED', 'LATE')
        ORDER BY a.professional.id ASC, a.scheduledTime ASC
    """)
    List<Attendance> findWaitingQueueByEstablishment(
            @Param("establishmentId") UUID establishmentId
    );

    /**
     * Filas de espera (SCHEDULED + LATE) de VARIOS profissionais de uma vez — segunda consulta do
     * resumo em lote da listagem publica (B10). Substitui uma chamada de
     * {@link #findWaitingQueueByProfessionalAndEstablishment} por loja.
     *
     * <p><b>Sem JOIN FETCH de proposito.</b> O ETA le so colunas escalares do proprio atendimento
     * ({@code estimatedMinutes}, marcas de inicio/pausa, {@code isFit}); {@code client},
     * {@code serviceItems} e companhia so existem para <b>serializar</b> o snapshot de detalhe, e
     * este caminho nao serializa atendimento nenhum — devolve dois numeros por loja.</p>
     *
     * <p>Devolve tuplas {@code [professionalId, establishmentId, attendance]} porque o resumo agrupa
     * pelo <b>par</b>: e o mesmo alcance da consulta por profissional+loja que ela substitui.
     * A ordem por {@code scheduledTime} e o que faz a fila ser fila — o calculo do ETA depende dela.</p>
     */
    @Query("""
        SELECT a.professional.id, a.establishment.id, a
        FROM Attendance a
        WHERE a.professional.id IN :professionalIds
          AND a.status IN ('SCHEDULED', 'LATE')
        ORDER BY a.scheduledTime ASC
    """)
    List<Object[]> findWaitingQueuesByProfessionalIds(
            @Param("professionalIds") java.util.Collection<UUID> professionalIds
    );

    /**
     * Atendimentos em curso (IN_PROGRESS ou PAUSED) de VARIOS profissionais — terceira consulta do
     * resumo em lote (B10). O desempate entre os dois status fica em Java, como no snapshot de
     * detalhe: IN_PROGRESS manda; PAUSED so entra na falta dele (Partner BUG7).
     *
     * <p>Tuplas {@code [professionalId, attendance]} — o corrente NAO e escopado por loja, exatamente
     * como em {@link #findCurrentAttendanceByProfessional}.</p>
     */
    @Query("""
        SELECT a.professional.id, a
        FROM Attendance a
        WHERE a.professional.id IN :professionalIds
          AND a.status IN ('IN_PROGRESS', 'PAUSED')
    """)
    List<Object[]> findCurrentAttendancesByProfessionalIds(
            @Param("professionalIds") java.util.Collection<UUID> professionalIds
    );

    /**
     * Find o attendance atualmente em progresso — JOIN FETCH para serialização segura.
     */
    @Query("""
        SELECT a FROM Attendance a
        LEFT JOIN FETCH a.client c
        LEFT JOIN FETCH c.baseUser
        LEFT JOIN FETCH a.professional p
        LEFT JOIN FETCH p.baseUser
        LEFT JOIN FETCH a.establishment
        LEFT JOIN FETCH a.serviceItems
        WHERE a.professional.id = :professionalId
          AND a.status = :status
    """)
    Optional<Attendance> findCurrentAttendanceByProfessional(
            @Param("professionalId") UUID professionalId,
            @Param("status") AttendanceStatus status
    );

    /**
     * Find attendances pausados (se precisar tratar separadamente)
     */
    @Query("""
        SELECT a FROM Attendance a
        WHERE a.professional.id = :professionalId
          AND a.establishment.id = :establishmentId
          AND a.status = 'PAUSED'
        ORDER BY a.scheduledTime ASC
    """)
    List<Attendance> findPausedAttendancesByProfessionalAndEstablishment(
            @Param("professionalId") UUID professionalId,
            @Param("establishmentId") UUID establishmentId
    );

    /**
     * Generic method to find attendances by a specific status
     * Useful for future flexibility
     */
    List<Attendance> findByProfessional_IdAndEstablishment_IdAndStatusOrderByScheduledTimeAsc(
            UUID professionalId,
            UUID establishmentId,
            AttendanceStatus status
    );

    /**
     * Finds attendances matching any of the given statuses
     * Useful when needing SCHEDULED + PAUSED, for example
     */
    @Query("""
        SELECT a FROM Attendance a
        WHERE a.professional.id = :professionalId
          AND a.establishment.id = :establishmentId
          AND a.status IN :statuses
        ORDER BY a.scheduledTime ASC
    """)
    List<Attendance> findByProfessionalAndEstablishmentAndStatusIn(
            @Param("professionalId") UUID professionalId,
            @Param("establishmentId") UUID establishmentId,
            @Param("statuses") List<AttendanceStatus> statuses
    );

    /**
     * Verifica se existe algum attendance em progresso para o profissional
     * Useful for validations before starting a new attendance
     */
    boolean existsByProfessional_IdAndEstablishment_IdAndStatus(
            UUID professionalId,
            UUID establishmentId,
            AttendanceStatus status
    );

    boolean existsByProfessional_IdAndStatusIn(UUID professionalId, List<AttendanceStatus> statuses);

    // Apagar a conta (Partner S19) — os tres portoes do 409 ACCOUNT_DELETE_NOT_ALLOWED:
    // o titular como CLIENTE (esta na fila de alguem), e a loja dele como DONO (tem fila viva).
    // O portao do PROFISSIONAL reusa o existsByProfessional_IdAndStatusIn acima.
    boolean existsByClient_IdAndStatusIn(UUID clientId, List<AttendanceStatus> statuses);

    boolean existsByEstablishment_IdAndStatusIn(UUID establishmentId, List<AttendanceStatus> statuses);

    /**
     * Item #38 — invariante do duplo ingresso: este cliente ja tem atendimento OCUPADO com este
     * profissional? Chamada no {@code createAttendance}, antes de qualquer escrita.
     *
     * <p><b>Raio estreito por decisao do Andre (D1, 15/08):</b> compara pelo par cliente x
     * profissional, sobre colunas que ja sao chave estrangeira e ja estao indexadas — a consulta nao
     * sai do estabelecimento que a requisicao ja carregou. O raio largo (mesmo {@code service_type}
     * em qualquer loja) exigiria varrer os atendimentos do cliente na base inteira e cruzar com o
     * tipo de cada estabelecimento; ficou registrado no D1 como decisao separada.
     */
    boolean existsByClient_IdAndProfessional_IdAndStatusIn(
            UUID clientId,
            UUID professionalId,
            List<AttendanceStatus> statuses
    );

    // S1 fatia 3 — fila propria do profissional (ex.: SCHEDULED/LATE) para o anuncio de "fila
    // fechada" aos clientes que ja estao dentro (drenagem: eles seguem sendo atendidos).
    List<Attendance> findByProfessional_IdAndStatusInOrderByScheduledTimeAsc(
            UUID professionalId, List<AttendanceStatus> statuses);

    /**
     * Counts how many attendances are waiting in the queue (SCHEDULED + LATE, item #19).
     */
    @Query("""
        SELECT COUNT(a) FROM Attendance a
        WHERE a.professional.id = :professionalId
          AND a.establishment.id = :establishmentId
          AND a.status IN ('SCHEDULED', 'LATE')
    """)
    long countWaitingQueueByProfessionalAndEstablishment(
            @Param("professionalId") UUID professionalId,
            @Param("establishmentId") UUID establishmentId
    );

    /**
     * Conta os atendimentos ATIVOS de um establishment — fila de espera (SCHEDULED, LATE)
     * mais os em curso (IN_PROGRESS, PAUSED). Base do gate que impede fechar a loja com
     * cliente na fila ou em atendimento (Partner BUG6). FINISHED/CANCELLED nao contam.
     */
    @Query("""
        SELECT COUNT(a) FROM Attendance a
        WHERE a.establishment.id = :establishmentId
          AND a.status IN ('SCHEDULED', 'LATE', 'IN_PROGRESS', 'PAUSED')
    """)
    long countActiveByEstablishment(@Param("establishmentId") UUID establishmentId);

    @Query(value = """
        SELECT DISTINCT a.* FROM attendance a
        LEFT JOIN client c ON c.id = a.client_id
        LEFT JOIN base_user bu1 ON bu1.id = c.id
        LEFT JOIN professional p ON p.id = a.professional_id
        LEFT JOIN base_user bu2 ON bu2.id = p.id
        LEFT JOIN establishment e ON e.id = a.establishment_id
        WHERE (CAST(:establishmentId AS uuid) IS NULL OR a.establishment_id = CAST(:establishmentId AS uuid))
          AND (CAST(:professionalId AS uuid) IS NULL OR a.professional_id = CAST(:professionalId AS uuid))
          AND (CAST(:status AS varchar) IS NULL OR a.status = CAST(:status AS varchar))
          AND (CAST(:dateFrom AS timestamptz) IS NULL OR a.scheduled_time >= CAST(:dateFrom AS timestamptz))
          AND (CAST(:dateTo AS timestamptz) IS NULL OR a.scheduled_time < CAST(:dateTo AS timestamptz))
        ORDER BY a.scheduled_time DESC
    """, nativeQuery = true)
    List<Attendance> findByFilters(
            @Param("establishmentId") UUID establishmentId,
            @Param("professionalId") UUID professionalId,
            @Param("status") String status,
            @Param("dateFrom") OffsetDateTime dateFrom,
            @Param("dateTo") OffsetDateTime dateTo
    );

    @Query("""
        SELECT DISTINCT a FROM Attendance a
        LEFT JOIN FETCH a.client c
        LEFT JOIN FETCH c.baseUser
        LEFT JOIN FETCH a.professional p
        LEFT JOIN FETCH p.baseUser
        LEFT JOIN FETCH a.establishment
        LEFT JOIN FETCH a.serviceItems
        WHERE a.client.id = :clientId
          AND (:status IS NULL OR a.status = :status)
          AND a.scheduledTime >= :dateFrom
          AND a.scheduledTime < :dateTo
        ORDER BY a.scheduledTime DESC
    """)
    List<Attendance> findByClientFilters(
            @Param("clientId") UUID clientId,
            @Param("status") AttendanceStatus status,
            @Param("dateFrom") OffsetDateTime dateFrom,
            @Param("dateTo") OffsetDateTime dateTo
    );

    /**
     * Atendimentos FINALIZADOS de um establishment num periodo — base dos relatorios
     * agregados do owner (#4/#5). JOIN FETCH de professional + serviceItems para
     * agregacao por profissional e por tipo de servico fora da sessao.
     */
    @Query("""
        SELECT DISTINCT a FROM Attendance a
        LEFT JOIN FETCH a.professional p
        LEFT JOIN FETCH p.baseUser
        LEFT JOIN FETCH a.serviceItems
        WHERE a.establishment.id = :establishmentId
          AND a.status = 'FINISHED'
          AND a.scheduledTime >= :dateFrom
          AND a.scheduledTime < :dateTo
    """)
    List<Attendance> findFinishedByEstablishmentBetween(
            @Param("establishmentId") UUID establishmentId,
            @Param("dateFrom") OffsetDateTime dateFrom,
            @Param("dateTo") OffsetDateTime dateTo
    );

    /**
     * Atendimentos FINALIZADOS de um profissional num periodo — base do relatorio
     * proprio do profissional (GET /professional/me/reports).
     */
    @Query("""
        SELECT DISTINCT a FROM Attendance a
        LEFT JOIN FETCH a.serviceItems
        WHERE a.professional.id = :professionalId
          AND a.status = 'FINISHED'
          AND a.scheduledTime >= :dateFrom
          AND a.scheduledTime < :dateTo
    """)
    List<Attendance> findFinishedByProfessionalBetween(
            @Param("professionalId") UUID professionalId,
            @Param("dateFrom") OffsetDateTime dateFrom,
            @Param("dateTo") OffsetDateTime dateTo
    );
}