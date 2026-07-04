package com.ospaulos.vupth.domain.notification.service;

import com.ospaulos.vupth.domain.notification.model.DeviceToken;

import java.util.UUID;

public interface DeviceTokenService {

    /**
     * Registra (ou reatribui) um device token ao usuario. Idempotente por {@code token}: se o token
     * ja existe, atualiza dono/plataforma; senao cria. Backlog #1.
     */
    DeviceToken register(UUID userId, String token, String platform);

    /** Remove um device token (logout). No-op se nao existir. */
    void remove(String token);
}
