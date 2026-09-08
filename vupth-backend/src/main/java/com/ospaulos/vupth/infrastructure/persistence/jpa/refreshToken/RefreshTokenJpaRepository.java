package com.ospaulos.vupth.infrastructure.persistence.jpa.refreshToken;

import com.ospaulos.vupth.domain.refreshToken.model.RefreshToken;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenJpaRepository extends JpaRepository<RefreshToken, UUID> {

    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.user.id = :userId")
    void deleteRefreshTokenByBaseUserId(@Param("userId") UUID userId);

    Optional<RefreshToken> findByToken(String refreshToken);

    void deleteByToken(RefreshToken refreshToken);

    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiryDate < :now OR rt.revokedAt IS NOT NULL")
    int deleteExpiredAndRevokedTokens(OffsetDateTime now);

    @Modifying
    @Query("""
    DELETE FROM RefreshToken rt
    WHERE rt.user.id = :userId
      AND rt.revokedAt IS NOT NULL
      AND rt.revokedAt < :limit
      """)
    void deleteOldRevokedTokens(UUID userId, OffsetDateTime limit);

    /**
     * Higiene por usuario: vencidos e revogados antigos saem; sessao viva de outro aparelho FICA.
     * O {@code replaced_by} tem ON DELETE SET NULL, entao apagar um sucessor ja morto nao explode
     * a FK — so desfaz um elo que ja nao valia mais nada.
     */
    @Modifying
    @Query("""
    DELETE FROM RefreshToken rt
    WHERE rt.user.id = :userId
      AND (rt.expiryDate < :now
           OR (rt.revokedAt IS NOT NULL AND rt.revokedAt < :revokedBefore))
    """)
    void deletePrunableByUserId(@Param("userId") UUID userId,
                                @Param("now") OffsetDateTime now,
                                @Param("revokedBefore") OffsetDateTime revokedBefore);

    @Query("SELECT COUNT(rt) FROM RefreshToken rt WHERE rt.user.id = :userId AND rt.revokedAt IS NULL AND rt.expiryDate > :now")
    long countActiveTokensByUserId(UUID userId, OffsetDateTime now);

    @Modifying
    @Query("""
        UPDATE RefreshToken rt
        SET rt.revokedAt = CURRENT_TIMESTAMP
        WHERE rt.user.id = :userId
          AND rt.revokedAt IS NULL
    """)
    void revokeAllByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query("""
    UPDATE RefreshToken rt
    SET rt.revokedAt = CURRENT_TIMESTAMP
    WHERE rt.token = :token
      AND rt.revokedAt IS NULL
""")
    int revokeByToken(@Param("token") String token);
}
