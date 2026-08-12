package com.ospaulos.vupth.domain.notification.repository;

import com.ospaulos.vupth.domain.notification.model.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    Optional<DeviceToken> findByToken(String token);

    List<DeviceToken> findByUserId(UUID userId);

    @Transactional
    void deleteByToken(String token);

    /**
     * Apagar a conta (Partner S19): sem isto o aparelho continuaria recebendo push de uma conta
     * que nao existe mais — o token e do APARELHO, nao do atendimento, entao some de verdade.
     */
    @Transactional
    void deleteByUserId(UUID userId);
}
