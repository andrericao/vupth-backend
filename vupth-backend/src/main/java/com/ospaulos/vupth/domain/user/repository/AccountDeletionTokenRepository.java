package com.ospaulos.vupth.domain.user.repository;

import com.ospaulos.vupth.domain.user.model.AccountDeletionToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** Links de exclusao de conta pela web (exclusao-por-link). No maximo um ativo por usuario. */
public interface AccountDeletionTokenRepository extends JpaRepository<AccountDeletionToken, UUID> {

    Optional<AccountDeletionToken> findByTokenHash(String tokenHash);

    /** Um link novo invalida os anteriores; a exclusao feita apaga todos. */
    @Modifying
    @Transactional
    @Query("DELETE FROM AccountDeletionToken t WHERE t.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
