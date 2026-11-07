package com.ospaulos.vupth.domain.admin.repository;

import com.ospaulos.vupth.domain.admin.model.AdminSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface AdminSessionRepository extends JpaRepository<AdminSession, UUID> {

    /** Trava a linha: duas abas renovando juntas viram uma depois da outra, e a segunda cai na folga do token anterior. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AdminSession s where s.tokenHash = :hash or s.previousHash = :hash")
    Optional<AdminSession> findForUpdate(@Param("hash") String hash);

    @Modifying
    @Query("update AdminSession s set s.revokedAt = :now where s.userId = :userId and s.revokedAt is null")
    int revokeAll(@Param("userId") UUID userId, @Param("now") OffsetDateTime now);

    /** Higiene ao abrir uma sessao: sai o que passou do teto; nada dela renova mais. */
    @Modifying
    @Query("delete from AdminSession s where s.userId = :userId and s.startedAt < :before")
    int deleteStartedBefore(@Param("userId") UUID userId, @Param("before") OffsetDateTime before);
}
