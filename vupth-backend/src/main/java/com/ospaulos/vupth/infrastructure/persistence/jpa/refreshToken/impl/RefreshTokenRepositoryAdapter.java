package com.ospaulos.vupth.infrastructure.persistence.jpa.refreshToken.impl;

import com.ospaulos.vupth.domain.refreshToken.model.RefreshToken;
import com.ospaulos.vupth.domain.refreshToken.repository.RefreshTokenRepository;
import com.ospaulos.vupth.infrastructure.persistence.jpa.refreshToken.RefreshTokenJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class RefreshTokenRepositoryAdapter implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository jpaRepository;

    @Override
    public RefreshToken saveRefreshToken(RefreshToken token){
        return jpaRepository.save(token);
    }

    @Override
    public Optional<RefreshToken> findByToken(String token){
        return jpaRepository.findByToken(token);
    }

    @Override
    public void deleteByToken(RefreshToken token){
        jpaRepository.deleteByToken(token);
    }

    @Override
    public void deleteByUserId(UUID id){
        jpaRepository.deleteRefreshTokenByBaseUserId(id);
    }

    @Override
    public int deleteExpiredAndRevokedTokens(OffsetDateTime now){
        return jpaRepository.deleteExpiredAndRevokedTokens(now);
    }

    @Override
    public long countActiveTokensByUserId(UUID id, OffsetDateTime now){
        return jpaRepository.countActiveTokensByUserId(id, now);
    }

    @Override
    public void revokeAllByUserId(UUID userId){
        jpaRepository.revokeAllByUserId(userId);
    }

    @Override
    public int revokeByToken(String token){
        return jpaRepository.revokeByToken(token);
    }

    @Override
    public void deleteOldRevokedTokens(UUID userId, OffsetDateTime limit){
        jpaRepository.deleteOldRevokedTokens(userId, limit);
    }
}
