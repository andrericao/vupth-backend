package com.ospaulos.vupth.domain.user.repository;

import com.ospaulos.vupth.domain.user.model.PasswordResetCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Códigos de reset de senha (backlog #1). Um usuário tem no máximo um código ativo por vez —
 * cada novo {@code forgot-password} apaga os anteriores.
 */
public interface PasswordResetCodeRepository extends JpaRepository<PasswordResetCode, UUID> {

    /** Código mais recente do usuário (o reset confere contra ele). */
    Optional<PasswordResetCode> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Invalida os códigos anteriores do usuário ao emitir um novo. */
    @Modifying
    @Transactional
    @Query("DELETE FROM PasswordResetCode c WHERE c.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
