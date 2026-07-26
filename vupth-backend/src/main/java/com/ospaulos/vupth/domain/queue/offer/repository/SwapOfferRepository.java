package com.ospaulos.vupth.domain.queue.offer.repository;

import com.ospaulos.vupth.domain.queue.offer.SwapOfferStatus;
import com.ospaulos.vupth.domain.queue.offer.model.SwapOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SwapOfferRepository extends JpaRepository<SwapOffer, UUID> {

    /** Carrega a oferta com solicitante/profissional/atendimento materializados. */
    @Query("""
            SELECT o FROM SwapOffer o
            JOIN FETCH o.requesterClient
            JOIN FETCH o.professional
            JOIN FETCH o.establishment
            LEFT JOIN FETCH o.requesterAttendance
            WHERE o.id = :id
            """)
    Optional<SwapOffer> findByIdEager(@Param("id") UUID id);

    /** Ofertas de um dado status na fila de um profissional (alimenta o GET, ordem estavel). */
    @Query("""
            SELECT o FROM SwapOffer o
            JOIN FETCH o.requesterClient
            LEFT JOIN FETCH o.requesterAttendance
            WHERE o.professional.id = :professionalId
              AND o.status = :status
            ORDER BY o.createdAt ASC
            """)
    List<SwapOffer> findByProfessionalAndStatus(@Param("professionalId") UUID professionalId,
                                                @Param("status") SwapOfferStatus status);

    /** Ofertas de um dado status cujo solicitante e o atendimento informado (expiracao por evento). */
    @Query("""
            SELECT o FROM SwapOffer o
            WHERE o.requesterAttendance.id = :attendanceId
              AND o.status = :status
            """)
    List<SwapOffer> findByRequesterAttendanceAndStatus(@Param("attendanceId") UUID attendanceId,
                                                       @Param("status") SwapOfferStatus status);

    /** Ofertas de um dado status publicadas por um cliente (lista "meus pedidos de troca"). */
    @Query("""
            SELECT o FROM SwapOffer o
            LEFT JOIN FETCH o.requesterAttendance
            WHERE o.requesterClient.id = :clientId
              AND o.status = :status
            ORDER BY o.createdAt DESC
            """)
    List<SwapOffer> findByRequesterClientAndStatus(@Param("clientId") UUID clientId,
                                                   @Param("status") SwapOfferStatus status);
}
