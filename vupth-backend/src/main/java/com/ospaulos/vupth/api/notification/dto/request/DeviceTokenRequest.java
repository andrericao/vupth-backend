package com.ospaulos.vupth.api.notification.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registro de device token para push (backlog #1).
 *
 * <ul>
 *   <li>{@code token} — obrigatorio, o Expo Push Token do aparelho.</li>
 *   <li>{@code platform} — opcional, "ios" | "android" | "web" (informativo).</li>
 * </ul>
 */
public record DeviceTokenRequest(

        @NotBlank(message = "Token is required")
        @Size(max = 512, message = "Token must be at most 512 characters")
        String token,

        @Size(max = 20, message = "Platform must be at most 20 characters")
        String platform
) {
}
