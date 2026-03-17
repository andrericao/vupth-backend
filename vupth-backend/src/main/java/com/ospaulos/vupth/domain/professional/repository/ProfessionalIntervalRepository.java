package com.ospaulos.vupth.domain.professional.repository;

import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ProfessionalIntervalRepository extends JpaRepository<ProfessionalInterval, UUID> {

    @Query("""
                SELECT pi FROM ProfessionalInterval pi
                WHERE pi.professional.id = :professionalId
                AND pi.isActive = true
                AND (pi.intervalType = 'RECURRENT' OR
                     (pi.intervalType = 'UNEXPECTED' AND
                      pi.startTime >= :startOfDay AND pi.startTime < :startOfNextDay))
            ORDER BY pi.fixedStartTime ASC, pi.startTime ASC
            """)
    //@Query("SELECT pi FROM ProfessionalInterval pi WHERE pi.professional.id = :professionalId")
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

    @Query(value = """
        SELECT COUNT(pi) > 0 FROM ProfessionalInterval pi
        WHERE pi.professional.id = :professionalId
        AND pi.isActive = true
        AND pi.intervalType = 'RECURRENT'
        AND :startTime < (pi.fixedStartTime + (pi.durationMinutes || ' minutes')::INTERVAL)::TIME
        AND (:startTime + (:durationMinutes || ' minutes')::INTERVAL) > pi.fixedStartTime
        """, nativeQuery = true)
    boolean existsRecurringIntervalConflict(@Param("professionalId") UUID professionalId,
                                            @Param("startTime") OffsetTime startTime,
                                            @Param("durationMinutes") Integer durationMinutes);

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
