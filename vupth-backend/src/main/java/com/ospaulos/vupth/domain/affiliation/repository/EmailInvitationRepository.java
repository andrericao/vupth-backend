package com.ospaulos.vupth.domain.affiliation.repository;

import com.ospaulos.vupth.domain.affiliation.model.EmailInvitation;
import com.ospaulos.vupth.domain.affiliation.model.EmailInvitationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface EmailInvitationRepository extends JpaRepository<EmailInvitation, UUID> {

    /** Convites esperando este e-mail, o mais novo primeiro (com a loja, para virar convite comum). */
    @Query("""
        SELECT i FROM EmailInvitation i
        JOIN FETCH i.establishment
        WHERE i.email = :email AND i.status = :status
        ORDER BY i.createdAt DESC
    """)
    List<EmailInvitation> findByEmailAndStatus(@Param("email") String email,
                                               @Param("status") EmailInvitationStatus status);

    @Query("""
        SELECT i FROM EmailInvitation i
        WHERE i.establishment.id = :establishmentId AND i.status = :status
        ORDER BY i.createdAt DESC
    """)
    List<EmailInvitation> findByEstablishmentAndStatus(@Param("establishmentId") UUID establishmentId,
                                                       @Param("status") EmailInvitationStatus status);

    boolean existsByEstablishment_IdAndEmailAndStatus(UUID establishmentId, String email,
                                                      EmailInvitationStatus status);
}
