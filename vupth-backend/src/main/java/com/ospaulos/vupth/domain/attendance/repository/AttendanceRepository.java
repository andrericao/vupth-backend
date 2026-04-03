package com.ospaulos.vupth.domain.attendance.repository;

import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
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
        ORDER BY a.scheduledTime ASC, a.createdAt ASC
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
     * Find apenas attendances em espera (SCHEDULED) para formar a fila.
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
          AND a.status = 'SCHEDULED'
        ORDER BY a.scheduledTime ASC, a.createdAt ASC
    """)
    List<Attendance> findWaitingQueueByProfessionalAndEstablishment(
            @Param("professionalId") UUID professionalId,
            @Param("establishmentId") UUID establishmentId
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

    /**
     * Counts how many attendances are waiting in the queue
     */
    @Query("""
        SELECT COUNT(a) FROM Attendance a
        WHERE a.professional.id = :professionalId
          AND a.establishment.id = :establishmentId
          AND a.status = 'SCHEDULED'
    """)
    long countWaitingQueueByProfessionalAndEstablishment(
            @Param("professionalId") UUID professionalId,
            @Param("establishmentId") UUID establishmentId
    );
}