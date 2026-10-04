package com.ospaulos.vupth.domain.affiliation.repository;

import com.ospaulos.vupth.domain.affiliation.model.AffiliationRequest;
import com.ospaulos.vupth.domain.affiliation.model.AffiliationStatus;
import com.ospaulos.vupth.domain.affiliation.model.AffiliationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AffiliationRequestRepository extends JpaRepository<AffiliationRequest, UUID> {

    boolean existsByProfessional_IdAndEstablishment_IdAndStatus(
            UUID professionalId, UUID establishmentId, AffiliationStatus status);

    // Pendencia unica global: existe alguma solicitacao (REQUEST) PENDING deste profissional?
    boolean existsByProfessional_IdAndTypeAndStatus(
            UUID professionalId, AffiliationType type, AffiliationStatus status);

    @Query("""
        SELECT ar FROM AffiliationRequest ar
        JOIN FETCH ar.professional p
        JOIN FETCH p.baseUser
        JOIN FETCH ar.establishment e
        WHERE ar.establishment.id = :establishmentId
          AND ar.status = :status
        ORDER BY ar.createdAt DESC
    """)
    List<AffiliationRequest> findByEstablishmentAndStatus(
            @Param("establishmentId") UUID establishmentId,
            @Param("status") AffiliationStatus status);

    /** Historico do vinculo da loja, todos os estados, mais novo primeiro (DEC-19). */
    @Query("""
        SELECT ar FROM AffiliationRequest ar
        JOIN FETCH ar.professional p
        JOIN FETCH p.baseUser
        JOIN FETCH ar.establishment e
        WHERE ar.establishment.id = :establishmentId
        ORDER BY ar.createdAt DESC
    """)
    List<AffiliationRequest> findByEstablishment(@Param("establishmentId") UUID establishmentId);

    /** Historico do vinculo do profissional, todos os estados, mais novo primeiro (DEC-19). */
    @Query("""
        SELECT ar FROM AffiliationRequest ar
        JOIN FETCH ar.professional p
        JOIN FETCH p.baseUser
        JOIN FETCH ar.establishment e
        WHERE ar.professional.id = :professionalId
        ORDER BY ar.createdAt DESC
    """)
    List<AffiliationRequest> findByProfessional(@Param("professionalId") UUID professionalId);

    @Query("""
        SELECT ar FROM AffiliationRequest ar
        JOIN FETCH ar.professional p
        JOIN FETCH p.baseUser
        JOIN FETCH ar.establishment e
        WHERE ar.professional.id = :professionalId
          AND ar.status = :status
        ORDER BY ar.createdAt DESC
    """)
    List<AffiliationRequest> findByProfessionalAndStatus(
            @Param("professionalId") UUID professionalId,
            @Param("status") AffiliationStatus status);

    @Query("""
        SELECT ar FROM AffiliationRequest ar
        JOIN FETCH ar.professional p
        JOIN FETCH p.baseUser
        JOIN FETCH ar.establishment e
        WHERE ar.id = :id
    """)
    Optional<AffiliationRequest> findByIdEager(@Param("id") UUID id);
}
