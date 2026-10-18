package com.ospaulos.vupth.domain.admin.repository;

import com.ospaulos.vupth.domain.admin.model.AdminInvitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminInvitationRepository extends JpaRepository<AdminInvitation, UUID> {

    Optional<AdminInvitation> findByTokenHash(String tokenHash);

    /** Os pendentes: nem aceitos, nem revogados, nem vencidos. */
    List<AdminInvitation> findByAcceptedAtIsNullAndRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(OffsetDateTime now);

    List<AdminInvitation> findByEmailIgnoreCaseAndAcceptedAtIsNullAndRevokedAtIsNull(String email);
}
