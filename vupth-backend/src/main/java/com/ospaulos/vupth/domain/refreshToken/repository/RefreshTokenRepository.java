package com.ospaulos.vupth.domain.refreshToken.repository;

import com.ospaulos.vupth.domain.refreshToken.model.RefreshToken;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {

    RefreshToken saveRefreshToken(RefreshToken refreshToken);

    Optional<RefreshToken> findByToken(String token);

    void deleteByToken(RefreshToken token);

    void deleteOldRevokedTokens(UUID userId, OffsetDateTime limit);

    void deleteByUserId(UUID userId);

    /**
     * Apaga os tokens MORTOS de um usuario: os vencidos e os revogados ha mais tempo que o limite.
     * Substitui o {@code deleteByUserId} que rodava a cada emissao — aquele apagava tambem as
     * sessoes VIVAS dos outros aparelhos, que e o defeito que este metodo existe para nao repetir.
     */
    void deletePrunableByUserId(UUID userId, OffsetDateTime now, OffsetDateTime revokedBefore);

    int deleteExpiredAndRevokedTokens(OffsetDateTime now);

    long countActiveTokensByUserId(UUID userId, OffsetDateTime now);

    void revokeAllByUserId(UUID userId);

    int revokeByToken(String token);
}
