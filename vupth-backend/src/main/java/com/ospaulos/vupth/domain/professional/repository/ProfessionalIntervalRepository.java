package com.ospaulos.vupth.domain.professional.repository;

import com.ospaulos.vupth.domain.interval.valueObject.IntervalType;
import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ProfessionalIntervalRepository extends JpaRepository<ProfessionalInterval, UUID> {

    /**
     * Intervalos de um profissional para um dia. Inclui as refeicoes recorrentes
     * tanto PREVISTAS (ainda nao iniciadas, {@code isActive=false}) quanto INICIADAS,
     * para que o front possa listar/editar almoco e janta e mostrar o botao "iniciar".
     * Exclui as recorrentes ja ENCERRADAS dentro do dia consultado ({@code actualEndTime}
     * no intervalo do dia) — refeicao ja feita hoje nao reaparece como previsto.
     * Para UNEXPECTED mantem o filtro original (ativo e dentro do dia).
     */
    @Query("""
                SELECT pi FROM ProfessionalInterval pi
                WHERE pi.professional.id = :professionalId
                AND (
                     (pi.intervalType = 'RECURRENT'
                      AND (pi.actualEndTime IS NULL
                           OR pi.actualEndTime < :startOfDay
                           OR pi.actualEndTime >= :startOfNextDay))
                     OR
                     (pi.intervalType = 'UNEXPECTED' AND pi.isActive = true
                      AND pi.startTime >= :startOfDay AND pi.startTime < :startOfNextDay))
            ORDER BY pi.fixedStartTime ASC, pi.startTime ASC
            """)
    List<ProfessionalInterval> findActiveIntervalsForProfessionalAndDate(
            @Param("professionalId") UUID professionalId,
            @Param("startOfDay") java.time.OffsetDateTime startOfDay,
            @Param("startOfNextDay") java.time.OffsetDateTime startOfNextDay);

    @Query(value = """
        SELECT COUNT(*) > 0 FROM professional_interval pi
    WHERE pi.professional_id = :professionalId
    AND pi.is_active = true
    AND (
        (pi.interval_type = 'UNEXPECTED'
         AND :dateTime BETWEEN pi.start_time AND pi.actual_end_time)
        OR
        (pi.interval_type = 'RECURRENT'
         AND :dateTime::TIME BETWEEN pi.fixed_start_time
         AND (pi.fixed_start_time + (pi.duration_minutes || ' minutes')::INTERVAL))
    )
    """, nativeQuery = true)
    boolean existsActiveIntervalAt(@Param("professionalId") UUID professionalId,
                                   @Param("dateTime") OffsetDateTime dateTime);

    /**
     * Intervalos recorrentes ativos de um profissional. Usado para checar conflito
     * de horario na criacao de recorrente (overlap calculado em Java no service).
     * Substitui a antiga existsRecurringIntervalConflict, que estava declarada como
     * nativeQuery mas escrita em JPQL (nomes de entidade/propriedade) — invalida e
     * nunca exercitada ate o POST /recurring entrar no Postman.
     */
    @Query("""
            SELECT pi FROM ProfessionalInterval pi
            WHERE pi.professional.id = :professionalId
            AND pi.intervalType = :intervalType
            AND pi.isActive = true
            """)
    List<ProfessionalInterval> findActiveByProfessionalAndType(
            @Param("professionalId") UUID professionalId,
            @Param("intervalType") IntervalType intervalType);

    /**
     * Intervalos ativos (is_active = true) de um profissional, qualquer tipo. Usado pelo
     * "encerrar ao atender" (spec item #11e): ao iniciar um atendimento, encerramos a
     * refeicao que o profissional iniciou manualmente e que ainda esta em curso.
     */
    List<ProfessionalInterval> findByProfessionalIdAndIsActiveTrue(UUID professionalId);

    // Claude v3
    @Query(value = """
    SELECT COUNT(*) > 0 FROM professional_intervals pi
    WHERE pi.professional_id = :professionalId
    AND pi.is_active = true
    AND pi.interval_type = 'UNEXPECTED'
    AND pi.start_time < :endTime
    AND (pi.start_time + CAST(pi.duration_minutes || ' minutes' AS INTERVAL)) > :startTime
    """, nativeQuery = true)
    boolean existsUnexpectedIntervalConflict(@Param("professionalId") UUID professionalId,
                                             @Param("startTime") OffsetDateTime startTime,
                                             @Param("endTime") OffsetDateTime endTime);
/*
    @Query(value = """
        SELECT COUNT(pi) > 0 FROM ProfessionalInterval pi
        WHERE pi.professional.id = :professionalId
        AND pi.isActive = true
        AND pi.intervalType = 'UNEXPECTED'
        AND :startTime < (pi.fixedStartTime + (pi.durationMinutes || ' minutes')::INTERVAL)
        AND (:startTime + (:durationMinutes || ' minutes')::INTERVAL) > pi.fixedStartTime
        """, nativeQuery = true)
    boolean existsUnexpectedIntervalConflict(@Param("professionalId") UUID professionalId,
                                            @Param("startTime") OffsetTime startTime,
                                            @Param("durationMinutes") Integer durationMinutes);
*/
}
