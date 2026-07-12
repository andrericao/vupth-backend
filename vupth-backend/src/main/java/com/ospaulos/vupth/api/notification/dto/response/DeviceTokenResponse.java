package com.ospaulos.vupth.api.notification.dto.response;

import com.ospaulos.vupth.domain.notification.model.DeviceToken;

import java.util.UUID;

/** Confirmacao do registro de device token (backlog #1). */
public record DeviceTokenResponse(
        UUID id,
        String token,
        String platform
) {
    public static DeviceTokenResponse fromEntity(DeviceToken d) {
        return new DeviceTokenResponse(d.getId(), d.getToken(), d.getPlatform());
    }
}
